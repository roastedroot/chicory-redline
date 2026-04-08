package com.dylibso.chicory.testing;

import com.dylibso.chicory.runtime.ImportValues;
import com.dylibso.chicory.runtime.Instance;
import com.dylibso.chicory.wasm.WasmModule;
import io.roastedroot.redline.api.RedlineInstance;
import io.roastedroot.redline.api.RedlineTarget;
import io.roastedroot.redline.compiler.internal.NativeCompiler;
import io.roastedroot.redline.runner.NativeMachineFactory;

/**
 * Test-only adapter that wraps {@link NativeMachineFactory.Builder} and
 * returns a plain {@link Instance} — matching what the spec-test harness expects.
 */
public final class NativeInstanceBuilder {

    private final RedlineInstance.Builder delegate;

    private NativeInstanceBuilder(RedlineInstance.Builder delegate) {
        this.delegate = delegate;
    }

    public static NativeInstanceBuilder builder(WasmModule module) {
        var b = NativeMachineFactory.builder(module);
        b.withCompilerFunction(m -> new NativeCompiler(RedlineTarget.detectHost(), m).compileAll());
        return new NativeInstanceBuilder(b);
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
        RedlineInstance ci = delegate.build();
        RedlineInstanceTracker.register(ci);
        return ci.instance();
    }
}
