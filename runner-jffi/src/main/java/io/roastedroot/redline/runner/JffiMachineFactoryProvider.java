package io.roastedroot.redline.runner;

import com.dylibso.chicory.runtime.Memory;
import com.dylibso.chicory.wasm.WasmModule;
import com.dylibso.chicory.wasm.types.MemoryLimits;
import io.roastedroot.redline.api.RedlineInstance;
import io.roastedroot.redline.api.RedlineMachineFactoryProvider;

/**
 * SPI provider for the jffi backend (Java 11+).
 */
public final class JffiMachineFactoryProvider implements RedlineMachineFactoryProvider {

    @Override
    public RedlineInstance.Builder builder(WasmModule module) {
        return JffiNativeMachineFactory.builder(module);
    }

    @Override
    public Memory createMemory(MemoryLimits limits) {
        return JffiNativeMachineFactory.createMemory(limits);
    }

    @Override
    public int priority() {
        return 50;
    }
}
