package io.roastedroot.cranelift.compiler.internal;

import com.dylibso.chicory.runtime.GlobalInstance;
import com.dylibso.chicory.wasm.types.MutabilityType;
import com.dylibso.chicory.wasm.types.ValType;
import com.dylibso.chicory.wasm.types.Value;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/**
 * GlobalInstance backed by an off-heap MemorySegment buffer.
 * Native code and Java code read/write the same memory — no sync needed.
 */
public final class NativeGlobalInstance extends GlobalInstance {

    private final MemorySegment buffer;
    private final long offset;

    public NativeGlobalInstance(
            MemorySegment buffer,
            int index,
            long initialValue,
            ValType valType,
            MutabilityType mutabilityType) {
        super(initialValue, 0, valType, mutabilityType);
        this.buffer = buffer;
        this.offset = (long) index * 8;
        // Write initial value to buffer
        buffer.set(ValueLayout.JAVA_LONG, offset, initialValue);
    }

    @Override
    public long getValue() {
        return buffer.get(ValueLayout.JAVA_LONG, offset);
    }

    @Override
    public long getValueLow() {
        return buffer.get(ValueLayout.JAVA_LONG, offset);
    }

    @Override
    public void setValue(long value) {
        buffer.set(ValueLayout.JAVA_LONG, offset, value);
    }

    @Override
    public void setValue(Value value) {
        buffer.set(ValueLayout.JAVA_LONG, offset, value.raw());
    }

    @Override
    public void setValueLow(long value) {
        buffer.set(ValueLayout.JAVA_LONG, offset, value);
    }
}
