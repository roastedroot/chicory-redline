package io.roastedroot.cranelift.runner;

import com.dylibso.chicory.runtime.ImportValues;
import com.dylibso.chicory.runtime.Instance;
import com.dylibso.chicory.wasm.types.MemoryLimits;

/**
 * AutoCloseable wrapper around a Chicory {@link Instance} backed by native
 * (Cranelift-compiled) machine code.
 *
 * <p>Closing this instance releases all off-heap resources (mmap'd memory,
 * code regions, Panama arenas). Use try-with-resources or call {@link #close()}
 * explicitly when done.
 */
public final class NativeInstance implements AutoCloseable {

    private final Instance instance;
    private final NativeMachineFactory factory;

    NativeInstance(Instance instance, NativeMachineFactory factory) {
        this.instance = instance;
        this.factory = factory;
    }

    /** Return the underlying Chicory {@link Instance}. */
    public Instance instance() {
        return instance;
    }

    @Override
    public void close() {
        factory.close();
    }

    /**
     * Builder that wraps {@link Instance.Builder} and produces a closeable
     * {@link NativeInstance}.
     */
    public static final class Builder {

        private final Instance.Builder instanceBuilder;
        private final NativeMachineFactory factory;

        public Builder(Instance.Builder instanceBuilder, NativeMachineFactory factory) {
            this.instanceBuilder = instanceBuilder;
            this.factory = factory;
        }

        public Builder withImportValues(ImportValues importValues) {
            instanceBuilder.withImportValues(importValues);
            return this;
        }

        public Builder withMemoryLimits(MemoryLimits limits) {
            instanceBuilder.withMemoryLimits(limits);
            return this;
        }

        public Builder withStart(boolean start) {
            instanceBuilder.withStart(start);
            return this;
        }

        public Builder withInitialize(boolean init) {
            instanceBuilder.withInitialize(init);
            return this;
        }

        public NativeInstance build() {
            return new NativeInstance(instanceBuilder.build(), factory);
        }
    }
}
