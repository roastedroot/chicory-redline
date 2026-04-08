package io.roastedroot.redline.runner;

import com.dylibso.chicory.runtime.Memory;
import com.dylibso.chicory.wasm.WasmModule;
import com.dylibso.chicory.wasm.types.MemoryLimits;
import io.roastedroot.redline.api.RedlineInstance;
import io.roastedroot.redline.api.RedlineMachineFactoryProvider;

/**
 * SPI provider for the Panama FFM backend (Java 25+).
 */
public final class PanamaMachineFactoryProvider implements RedlineMachineFactoryProvider {

    @Override
    public RedlineInstance.Builder builder(WasmModule module) {
        return NativeMachineFactory.builder(module);
    }

    @Override
    public Memory createMemory(MemoryLimits limits) {
        return NativeMachineFactory.createMemory(limits);
    }

    @Override
    public int priority() {
        return 100;
    }
}
