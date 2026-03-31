package io.roastedroot.cranelift.runner;

import com.dylibso.chicory.runtime.Instance;

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
}
