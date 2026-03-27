package io.roastedroot.cranelift.runner.internal;

import static com.dylibso.chicory.runtime.ConstantEvaluators.computeConstantValue;

import com.dylibso.chicory.runtime.Instance;
import com.dylibso.chicory.runtime.Memory;
import com.dylibso.chicory.wasm.ChicoryException;
import com.dylibso.chicory.wasm.UninstantiableException;
import com.dylibso.chicory.wasm.types.ActiveDataSegment;
import com.dylibso.chicory.wasm.types.DataSegment;
import com.dylibso.chicory.wasm.types.MemoryLimits;
import com.dylibso.chicory.wasm.types.PassiveDataSegment;
import io.roastedroot.cranelift.compiler.internal.CtxBuffer;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.ref.Cleaner;
import java.nio.ByteOrder;

/**
 * Off-heap contiguous Memory backed by mmap/mprotect.
 *
 * <p>Reserves the full maximum address range upfront with PROT_NONE (no physical
 * memory committed), then mprotects pages as needed. Grow is zero-copy — just
 * mprotect the next range. The base address never changes.
 */
public final class NativeMemory implements Memory, AutoCloseable {

    private static final Cleaner CLEANER = Cleaner.create();

    private final MemoryLimits limits;
    private final MemorySegment reserved;
    private MemorySegment segment;
    private int nPages;
    private final long reservedSize;
    private final Cleaner.Cleanable cleanable;
    private DataSegment[] dataSegments;
    // Optional ctxBuffer sink — when set, grow() writes updated pages here
    // so native code always sees current bounds without explicit refresh.
    private MemorySegment ctxBuffer;

    private record CleanupAction(MemorySegment reserved, long reservedSize) implements Runnable {
        @Override
        public void run() {
            if (reservedSize > 0) {
                try {
                    PanamaExecutor.munmap(reserved, reservedSize);
                } catch (Throwable e) {
                    // ignore cleanup errors
                }
            }
        }
    }

    public NativeMemory(MemoryLimits limits) {
        this.limits = limits;
        this.nPages = limits.initialPages();
        int maxPages = Math.min(limits.maximumPages(), RUNTIME_MAX_PAGES);
        this.reservedSize = PAGE_SIZE * (long) maxPages;

        try {
            this.reserved = PanamaExecutor.mmapNoAccess(reservedSize);

            if (nPages > 0) {
                PanamaExecutor.mprotectReadWrite(reserved, PAGE_SIZE * (long) nPages);
            }
        } catch (Throwable t) {
            throw new ChicoryException("Failed to mmap native memory", t);
        }

        this.segment = reserved.reinterpret(PAGE_SIZE * (long) nPages);
        this.cleanable = CLEANER.register(this, new CleanupAction(reserved, reservedSize));
    }

    /** Explicitly release the mmap'd memory region. Idempotent. */
    @Override
    public void close() {
        cleanable.clean();
    }

    /**
     * Register the ctxBuffer so that {@link #grow} writes updated page count
     * directly to {@link CtxBuffer#MEMORY_PAGES}. This makes ctxBuffer the
     * single source of truth — no matter which execution path calls grow()
     * (native upcall, bytecode machine, Java test harness), the native code's
     * bounds check always sees the current value.
     */
    public void setCtxBuffer(MemorySegment ctxBuffer) {
        this.ctxBuffer = ctxBuffer;
    }

    /** Get the native address of the memory buffer, for passing to native code. */
    public MemorySegment nativeAddress() {
        return segment;
    }

    @Override
    public int pages() {
        return nPages;
    }

    @Override
    public int grow(int size) {
        var prevPages = nPages;
        var numPages = prevPages + size;
        if (numPages > maximumPages() || numPages < prevPages) {
            return -1;
        }

        try {
            long newSize = PAGE_SIZE * (long) numPages;
            PanamaExecutor.mprotectReadWrite(reserved, newSize);
            this.segment = reserved.reinterpret(newSize);
            this.nPages = numPages;
            if (ctxBuffer != null) {
                ctxBuffer.set(ValueLayout.JAVA_INT, CtxBuffer.MEMORY_PAGES, numPages);
            }
        } catch (Throwable t) {
            return -1;
        }

        return prevPages;
    }

    @Override
    public int initialPages() {
        return limits.initialPages();
    }

    @Override
    public int maximumPages() {
        return Math.min(limits.maximumPages(), RUNTIME_MAX_PAGES);
    }

    @Override
    public boolean shared() {
        return limits.shared();
    }

    @SuppressWarnings("removal")
    @Override
    public Object lock(int address) {
        return new Object();
    }

    @SuppressWarnings("removal")
    @Override
    public int waitOn(int address, int expected, long timeout) {
        throw new ChicoryException("waitOn not supported on NativeMemory");
    }

    @SuppressWarnings("removal")
    @Override
    public int waitOn(int address, long expected, long timeout) {
        throw new ChicoryException("waitOn not supported on NativeMemory");
    }

    @SuppressWarnings("removal")
    @Override
    public int notify(int address, int maxThreads) {
        return 0;
    }

    @Override
    public void initialize(Instance instance, DataSegment[] dataSegments) {
        this.dataSegments = dataSegments;
        if (dataSegments == null) {
            return;
        }
        for (var s : dataSegments) {
            if (s instanceof ActiveDataSegment) {
                var seg = (ActiveDataSegment) s;
                var data = seg.data();
                var offset = (int) computeConstantValue(instance, seg.offsetInstructions())[0];
                if (offset < 0 || offset + data.length > sizeInBytes()) {
                    throw new UninstantiableException(
                            "out of bounds memory access: offset="
                                    + offset
                                    + " size="
                                    + data.length);
                }
                MemorySegment.copy(MemorySegment.ofArray(data), 0, segment, offset, data.length);
            }
        }
    }

    @Override
    public void initPassiveSegment(int segmentId, int dest, int offset, int size) {
        var seg = dataSegments[segmentId];
        if (seg == null || seg == com.dylibso.chicory.wasm.types.PassiveDataSegment.EMPTY) {
            if (size > 0) {
                throw new com.dylibso.chicory.runtime.WasmRuntimeException(
                        "out of bounds memory access");
            }
            return;
        }
        write(dest, seg.data(), offset, size);
    }

    private int sizeInBytes() {
        return PAGE_SIZE * nPages;
    }

    @Override
    public void write(int addr, byte[] data, int offset, int size) {
        long limit = segment.byteSize();
        if (Integer.toUnsignedLong(offset) + Integer.toUnsignedLong(size) > data.length
                || Integer.toUnsignedLong(addr) + Integer.toUnsignedLong(size) > limit) {
            throw new com.dylibso.chicory.runtime.WasmRuntimeException(
                    "out of bounds memory access");
        }
        MemorySegment.copy(MemorySegment.ofArray(data), offset, segment, addr, size);
    }

    @Override
    public byte read(int addr) {
        return segment.get(ValueLayout.JAVA_BYTE, addr);
    }

    @Override
    public byte[] readBytes(int addr, int len) {
        return segment.asSlice(addr, len).toArray(ValueLayout.JAVA_BYTE);
    }

    @Override
    public void writeI32(int addr, int data) {
        segment.set(ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN), addr, data);
    }

    @Override
    public int readInt(int addr) {
        return segment.get(ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN), addr);
    }

    @Override
    public void writeLong(int addr, long data) {
        segment.set(ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN), addr, data);
    }

    @Override
    public long readLong(int addr) {
        return segment.get(
                ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN), addr);
    }

    @Override
    public void writeShort(int addr, short data) {
        segment.set(
                ValueLayout.JAVA_SHORT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN), addr, data);
    }

    @Override
    public short readShort(int addr) {
        return segment.get(
                ValueLayout.JAVA_SHORT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN), addr);
    }

    @Override
    public long readU16(int addr) {
        return readShort(addr) & 0xFFFFL;
    }

    @Override
    public void writeByte(int addr, byte data) {
        segment.set(ValueLayout.JAVA_BYTE, addr, data);
    }

    @Override
    public void writeF32(int addr, float data) {
        writeI32(addr, Float.floatToRawIntBits(data));
    }

    @Override
    public long readF32(int addr) {
        return readInt(addr);
    }

    @Override
    public float readFloat(int addr) {
        return Float.intBitsToFloat(readInt(addr));
    }

    @Override
    public void writeF64(int addr, double data) {
        writeLong(addr, Double.doubleToRawLongBits(data));
    }

    @Override
    public double readDouble(int addr) {
        return Double.longBitsToDouble(readLong(addr));
    }

    @Override
    public long readF64(int addr) {
        return readLong(addr);
    }

    @Override
    public void zero() {
        segment.fill((byte) 0);
    }

    @Override
    public void copy(int dest, int src, int size) {
        long limit = segment.byteSize();
        if (Integer.toUnsignedLong(src) + Integer.toUnsignedLong(size) > limit
                || Integer.toUnsignedLong(dest) + Integer.toUnsignedLong(size) > limit) {
            throw new com.dylibso.chicory.runtime.WasmRuntimeException(
                    "out of bounds memory access");
        }
        MemorySegment.copy(segment, src, segment, dest, size);
    }

    @Override
    public void fill(byte value, int fromIndex, int toIndex) {
        long limit = segment.byteSize();
        if (Integer.toUnsignedLong(fromIndex) > limit
                || Integer.toUnsignedLong(toIndex) > limit
                || fromIndex > toIndex) {
            throw new com.dylibso.chicory.runtime.WasmRuntimeException(
                    "out of bounds memory access");
        }
        segment.asSlice(fromIndex, toIndex - fromIndex).fill(value);
    }

    @Override
    public void drop(int segment) {
        if (dataSegments != null) {
            dataSegments[segment] = PassiveDataSegment.EMPTY;
        }
    }
}
