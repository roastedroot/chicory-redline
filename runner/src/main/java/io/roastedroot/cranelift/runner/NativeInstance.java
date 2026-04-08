package io.roastedroot.cranelift.runner;

import com.dylibso.chicory.runtime.Instance;
import io.roastedroot.cranelift.api.CraneliftInstance;

/**
 * AutoCloseable wrapper around a Chicory {@link Instance} backed by native
 * (Cranelift-compiled) machine code.
 *
 * <p>This is a convenience alias for {@link CraneliftInstance} specific to the
 * Panama backend. Prefer using {@link CraneliftInstance} directly for
 * backend-independent code.
 *
 * <p>Closing this instance releases all off-heap resources (mmap'd memory,
 * code regions, Panama arenas). Use try-with-resources or call {@link #close()}
 * explicitly when done.
 */
public final class NativeInstance implements AutoCloseable {

    private final CraneliftInstance delegate;

    NativeInstance(CraneliftInstance delegate) {
        this.delegate = delegate;
    }

    /** Return the underlying Chicory {@link Instance}. */
    public Instance instance() {
        return delegate.instance();
    }

    @Override
    public void close() {
        delegate.close();
    }
}
