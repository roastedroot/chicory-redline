package com.dylibso.chicory.testing;

import com.dylibso.chicory.runtime.Instance;
import com.dylibso.chicory.wasm.WasmModule;
import io.roastedroot.cranelift.runner.NativeMachineFactory;

public final class NativeInstanceBuilder {

    private NativeInstanceBuilder() {}

    public static Instance.Builder builder(WasmModule module) {
        var factory = new NativeMachineFactory(module);
        return Instance.builder(module)
                .withMachineFactory(factory::compile)
                .withTableFactory(factory::createTable)
                .withGlobalFactory(factory::createGlobal)
                .withMemoryFactory(NativeMachineFactory::createMemory);
    }
}
