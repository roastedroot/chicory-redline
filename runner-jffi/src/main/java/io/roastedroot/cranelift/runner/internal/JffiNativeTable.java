package io.roastedroot.cranelift.runner.internal;

import static com.dylibso.chicory.wasm.types.Value.REF_NULL_VALUE;

import com.dylibso.chicory.runtime.Instance;
import com.dylibso.chicory.runtime.TableInstance;
import com.dylibso.chicory.wasm.ChicoryException;
import com.dylibso.chicory.wasm.types.Table;
import com.dylibso.chicory.wasm.types.TableLimits;
import com.dylibso.chicory.wasm.types.ValType;
import com.kenai.jffi.MemoryIO;
import io.roastedroot.cranelift.api.internal.CtxBuffer;

/**
 * Off-heap table implementation for native compilation via jffi.
 *
 * <p>Layout: [size:i32 @ 0][max:i32 @ 4][entries... @ 8]
 *
 * <p>Each entry is 16 bytes:
 * <pre>
 *   [0..4)   i32  canonicalTypeIdx
 *   [4..8)   i32  funcId
 *   [8..16)  i64  funcPtr (native address)
 * </pre>
 */
public final class JffiNativeTable extends TableInstance {

    private static final int MAX_PREALLOC = 1_000_000;
    private static final MemoryIO MEM = MemoryIO.getInstance();

    private final long bufferAddress;
    private final int capacity;

    public JffiNativeTable(Table table) {
        super(table, REF_NULL_VALUE);
        int initial = (int) table.limits().min();
        int max = (int) table.limits().max();
        this.capacity = (max > 0 && max <= MAX_PREALLOC) ? max : Math.max(initial, MAX_PREALLOC);
        long bufferSize =
                CtxBuffer.TABLE_ENTRIES_OFFSET + (long) capacity * CtxBuffer.TABLE_ENTRY_SIZE;
        this.bufferAddress = MEM.allocateMemory(bufferSize, true);

        // Write header
        MEM.putInt(bufferAddress + CtxBuffer.TABLE_SIZE_OFFSET, initial);
        MEM.putInt(bufferAddress + CtxBuffer.TABLE_MAX_OFFSET, max > 0 ? max : capacity);

        // Fill all entries with null (funcId=-1, funcPtr=0, typeIdx=0)
        for (int i = 0; i < capacity; i++) {
            writeNullEntry(i);
        }
    }

    private long entryBase(int index) {
        return CtxBuffer.TABLE_ENTRIES_OFFSET + (long) index * CtxBuffer.TABLE_ENTRY_SIZE;
    }

    private void writeNullEntry(int index) {
        long base = bufferAddress + entryBase(index);
        MEM.putInt(base + CtxBuffer.ENTRY_TYPE_IDX_OFFSET, 0);
        MEM.putInt(base + CtxBuffer.ENTRY_FUNC_ID_OFFSET, REF_NULL_VALUE);
        MEM.putLong(base + CtxBuffer.ENTRY_FUNC_PTR_OFFSET, 0L);
    }

    private void writeResolvedEntry(int index, int funcId, long ftAddr, long ftSize, long ftaAddr) {
        long base = bufferAddress + entryBase(index);
        int totalFuncs = (int) (ftSize / 8);
        if (funcId >= 0 && funcId < totalFuncs) {
            long funcPtr = MEM.getLong(ftAddr + (long) funcId * 8);
            int typeIdx = MEM.getInt(ftaAddr + (long) funcId * 4);
            MEM.putInt(base + CtxBuffer.ENTRY_TYPE_IDX_OFFSET, typeIdx);
            MEM.putInt(base + CtxBuffer.ENTRY_FUNC_ID_OFFSET, funcId);
            MEM.putLong(base + CtxBuffer.ENTRY_FUNC_PTR_OFFSET, funcPtr);
        } else {
            // Externref: store opaque ref value, not callable
            MEM.putInt(base + CtxBuffer.ENTRY_TYPE_IDX_OFFSET, 0);
            MEM.putInt(base + CtxBuffer.ENTRY_FUNC_ID_OFFSET, funcId);
            MEM.putLong(base + CtxBuffer.ENTRY_FUNC_PTR_OFFSET, 0L);
        }
    }

    private boolean resolveFromInstance(int index, int funcId, Instance instance) {
        if (instance != null && instance.getMachine() instanceof JffiNativeMachine) {
            JffiNativeMachine nm = (JffiNativeMachine) instance.getMachine();
            writeResolvedEntry(
                    index,
                    funcId,
                    nm.getFuncTableAddress(),
                    nm.getFuncTableSize(),
                    nm.getFuncTypesArrayAddress());
            return true;
        }
        return false;
    }

    /** Get the native address of the table buffer, for passing to native code. */
    long nativeBufferAddress() {
        return bufferAddress;
    }

    /** Free the off-heap buffer. */
    void free() {
        if (bufferAddress != 0) {
            MEM.freeMemory(bufferAddress);
        }
    }

    @Override
    public int size() {
        return MEM.getInt(bufferAddress + CtxBuffer.TABLE_SIZE_OFFSET);
    }

    @Override
    public ValType elementType() {
        return super.elementType();
    }

    @Override
    public TableLimits limits() {
        return super.limits();
    }

    @Override
    public int ref(int index) {
        if (index < 0 || index >= size()) {
            throw new ChicoryException("undefined element");
        }
        long base = bufferAddress + entryBase(index);
        return MEM.getInt(base + CtxBuffer.ENTRY_FUNC_ID_OFFSET);
    }

    @Override
    public int requiredRef(int index) {
        int r = ref(index);
        if (r == REF_NULL_VALUE) {
            throw new ChicoryException("uninitialized element " + index);
        }
        return r;
    }

    @Override
    public void setRef(int index, int value, Instance instance) {
        if (index < 0 || index >= size()) {
            throw new ChicoryException("out of bounds table access");
        }
        if (value == REF_NULL_VALUE) {
            writeNullEntry(index);
        } else if (resolveFromInstance(index, value, instance)) {
            // Resolved using the calling module's NativeMachine
        } else {
            // No NativeMachine available — store funcId only (externref or non-native)
            long base = bufferAddress + entryBase(index);
            MEM.putInt(base + CtxBuffer.ENTRY_TYPE_IDX_OFFSET, 0);
            MEM.putInt(base + CtxBuffer.ENTRY_FUNC_ID_OFFSET, value);
            MEM.putLong(base + CtxBuffer.ENTRY_FUNC_PTR_OFFSET, 0L);
        }
    }

    @Override
    public int grow(int delta, int value, Instance instance) {
        int oldSize = size();
        int newSize = oldSize + delta;
        int max = MEM.getInt(bufferAddress + CtxBuffer.TABLE_MAX_OFFSET);
        if (delta < 0 || newSize > max || newSize > capacity) {
            return -1;
        }
        // Fill new slots
        for (int i = oldSize; i < newSize; i++) {
            if (value == REF_NULL_VALUE) {
                writeNullEntry(i);
            } else if (!resolveFromInstance(i, value, instance)) {
                long base = bufferAddress + entryBase(i);
                MEM.putInt(base + CtxBuffer.ENTRY_TYPE_IDX_OFFSET, 0);
                MEM.putInt(base + CtxBuffer.ENTRY_FUNC_ID_OFFSET, value);
                MEM.putLong(base + CtxBuffer.ENTRY_FUNC_PTR_OFFSET, 0L);
            }
        }
        // Update size
        MEM.putInt(bufferAddress + CtxBuffer.TABLE_SIZE_OFFSET, newSize);
        limits().grow(delta);
        return oldSize;
    }

    @Override
    public Instance instance(int index) {
        return null;
    }

    @Override
    public void reset() {
        int sz = size();
        for (int i = 0; i < sz; i++) {
            writeNullEntry(i);
        }
    }
}
