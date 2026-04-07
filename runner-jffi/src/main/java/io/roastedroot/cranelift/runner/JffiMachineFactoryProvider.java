package io.roastedroot.cranelift.runner;

import com.dylibso.chicory.runtime.Memory;
import com.dylibso.chicory.wasm.WasmModule;
import com.dylibso.chicory.wasm.types.MemoryLimits;
import io.roastedroot.cranelift.compiler.CraneliftInstance;
import io.roastedroot.cranelift.compiler.CraneliftMachineFactoryProvider;

/**
 * SPI provider for the jffi backend (Java 11+).
 */
public final class JffiMachineFactoryProvider implements CraneliftMachineFactoryProvider {

    @Override
    public CraneliftInstance.Builder builder(WasmModule module) {
        return JffiNativeMachineFactory.builder(module);
    }

    @Override
    public Memory createMemory(MemoryLimits limits) {
        return JffiNativeMachineFactory.createMemory(limits);
    }
}
