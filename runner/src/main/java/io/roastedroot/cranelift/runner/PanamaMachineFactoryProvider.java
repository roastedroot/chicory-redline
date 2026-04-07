package io.roastedroot.cranelift.runner;

import com.dylibso.chicory.runtime.Memory;
import com.dylibso.chicory.wasm.WasmModule;
import com.dylibso.chicory.wasm.types.MemoryLimits;
import io.roastedroot.cranelift.compiler.CraneliftInstance;
import io.roastedroot.cranelift.compiler.CraneliftMachineFactoryProvider;

/**
 * SPI provider for the Panama FFM backend (Java 25+).
 */
public final class PanamaMachineFactoryProvider implements CraneliftMachineFactoryProvider {

    @Override
    public CraneliftInstance.Builder builder(WasmModule module) {
        return NativeMachineFactory.builder(module);
    }

    @Override
    public Memory createMemory(MemoryLimits limits) {
        return NativeMachineFactory.createMemory(limits);
    }
}
