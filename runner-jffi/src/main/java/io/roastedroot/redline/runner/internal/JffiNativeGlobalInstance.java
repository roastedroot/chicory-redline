package io.roastedroot.redline.runner.internal;

import com.dylibso.chicory.runtime.GlobalInstance;
import com.dylibso.chicory.wasm.types.MutabilityType;
import com.dylibso.chicory.wasm.types.ValType;
import com.dylibso.chicory.wasm.types.Value;
import com.kenai.jffi.MemoryIO;

/**
 * GlobalInstance backed by an off-heap buffer via jffi MemoryIO.
 * Native code and Java code read/write the same memory — no sync needed.
 */
public final class JffiNativeGlobalInstance extends GlobalInstance {

    private static final MemoryIO MEM = MemoryIO.getInstance();

    private final long bufferAddress;
    private final long offset;

    public JffiNativeGlobalInstance(
            long bufferAddress,
            int index,
            long initialValue,
            ValType valType,
            MutabilityType mutabilityType) {
        super(initialValue, 0, valType, mutabilityType);
        this.bufferAddress = bufferAddress;
        this.offset = (long) index * 8;
        // Write initial value to buffer
        MEM.putLong(bufferAddress + offset, initialValue);
    }

    @Override
    public long getValue() {
        return MEM.getLong(bufferAddress + offset);
    }

    @Override
    public long getValueLow() {
        return MEM.getLong(bufferAddress + offset);
    }

    @Override
    public void setValue(long value) {
        MEM.putLong(bufferAddress + offset, value);
    }

    @Override
    public void setValue(Value value) {
        MEM.putLong(bufferAddress + offset, value.raw());
    }

    @Override
    public void setValueLow(long value) {
        MEM.putLong(bufferAddress + offset, value);
    }
}
