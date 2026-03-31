package com.dylibso.chicory.testing;

import com.dylibso.chicory.runtime.ImportValues;
import com.dylibso.chicory.runtime.Instance;
import com.dylibso.chicory.wasm.WasmModule;
import io.roastedroot.cranelift.runner.NativeMachineFactory;

/**
 * Test-only adapter that wraps {@link NativeMachineFactory.Builder} and
 * returns a plain {@link Instance} — matching what the spec-test harness expects.
 */
public final class NativeInstanceBuilder {

    private final NativeMachineFactory.Builder delegate;

    private NativeInstanceBuilder(NativeMachineFactory.Builder delegate) {
        this.delegate = delegate;
    }

    public static NativeInstanceBuilder builder(WasmModule module) {
        return new NativeInstanceBuilder(NativeMachineFactory.builder(module));
    }

    public NativeInstanceBuilder withImportValues(ImportValues importValues) {
        delegate.withImportValues(importValues);
        return this;
    }

    public NativeInstanceBuilder withStart(boolean start) {
        delegate.withStart(start);
        return this;
    }

    public Instance build() {
        return delegate.build().instance();
    }
}
