package io.roastedroot.redline.runner;

import com.dylibso.chicory.runtime.Instance;
import io.roastedroot.redline.api.RedlineInstance;

/**
 * AutoCloseable wrapper around a Chicory {@link Instance} backed by native
 * (Redline-compiled) machine code via jffi.
 *
 * <p>This is a convenience alias for {@link RedlineInstance} specific to the
 * jffi backend. Prefer using {@link RedlineInstance} directly for
 * backend-independent code.
 *
 * <p>Closing this instance releases all off-heap resources (mmap'd memory,
 * code regions, jffi closures). Use try-with-resources or call {@link #close()}
 * explicitly when done.
 */
public final class JffiNativeInstance implements AutoCloseable {

    private final RedlineInstance delegate;

    JffiNativeInstance(RedlineInstance delegate) {
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
