package io.roastedroot.cranelift.runner;

import com.dylibso.chicory.runtime.Instance;
import io.roastedroot.cranelift.compiler.CraneliftInstance;

/**
 * AutoCloseable wrapper around a Chicory {@link Instance} backed by native
 * (Cranelift-compiled) machine code via jffi.
 *
 * <p>This is a convenience alias for {@link CraneliftInstance} specific to the
 * jffi backend. Prefer using {@link CraneliftInstance} directly for
 * backend-independent code.
 *
 * <p>Closing this instance releases all off-heap resources (mmap'd memory,
 * code regions, jffi closures). Use try-with-resources or call {@link #close()}
 * explicitly when done.
 */
public final class JffiNativeInstance implements AutoCloseable {

    private final CraneliftInstance delegate;

    JffiNativeInstance(CraneliftInstance delegate) {
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
