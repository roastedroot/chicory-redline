package io.roastedroot.redline.runner;

import com.dylibso.chicory.runtime.GlobalInstance;
import com.dylibso.chicory.runtime.ImportValues;
import com.dylibso.chicory.runtime.Instance;
import com.dylibso.chicory.runtime.Machine;
import com.dylibso.chicory.runtime.Memory;
import com.dylibso.chicory.runtime.TableInstance;
import com.dylibso.chicory.wasm.WasmModule;
import com.dylibso.chicory.wasm.types.MemoryLimits;
import com.dylibso.chicory.wasm.types.MutabilityType;
import com.dylibso.chicory.wasm.types.Table;
import com.dylibso.chicory.wasm.types.ValType;
import com.kenai.jffi.MemoryIO;
import io.roastedroot.redline.api.Interruptable;
import io.roastedroot.redline.api.RedlineInstance;
import io.roastedroot.redline.runner.internal.JffiNativeGlobalInstance;
import io.roastedroot.redline.runner.internal.JffiNativeMachine;
import io.roastedroot.redline.runner.internal.JffiNativeMemory;
import io.roastedroot.redline.runner.internal.JffiNativeTable;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Public API for the Redline native compiler (jffi backend, Java 11+).
 *
 * <p>Usage:
 * <pre>
 * try (var ni = JffiNativeMachineFactory.builder(module).build()) {
 *     ni.instance().export("func").apply();
 * }
 * </pre>
 */
public final class JffiNativeMachineFactory implements AutoCloseable, Interruptable {

    private static final MemoryIO MEM = MemoryIO.getInstance();

    private final WasmModule module;
    private final byte[][] precompiledCode;
    private final Function<WasmModule, byte[][]> compilerFunction;
    private final List<JffiNativeTable> nativeTables = new ArrayList<>();
    private long globalsBufferAddr;
    private int globalIndex;
    private JffiNativeMachine nativeMachine;

    private JffiNativeMachineFactory(
            WasmModule module,
            byte[][] precompiledCode,
            Function<WasmModule, byte[][]> compilerFunction) {
        this.module = module;
        this.precompiledCode = precompiledCode;
        this.compilerFunction = compilerFunction;

        // Pre-allocate globals buffer
        int importGlobalCount =
                (int)
                        module.importSection().stream()
                                .filter(
                                        i ->
                                                i.importType()
                                                        == com.dylibso.chicory.wasm.types
                                                                .ExternalType.GLOBAL)
                                .count();
        int definedGlobalCount =
                module.globalSection() != null ? module.globalSection().globalCount() : 0;
        int totalGlobals = importGlobalCount + definedGlobalCount;
        this.globalsBufferAddr =
                totalGlobals > 0 ? MEM.allocateMemory((long) totalGlobals * 8, true) : 0L;
        this.globalIndex = importGlobalCount; // module globals start after imports
    }

    public static Builder builder(WasmModule module) {
        return new Builder(module);
    }

    public TableInstance createTable(Table table, int initValue) {
        var nativeTable = new JffiNativeTable(table);
        nativeTables.add(nativeTable);
        return nativeTable;
    }

    public GlobalInstance createGlobal(
            long value, long highValue, ValType type, MutabilityType mutability) {
        return new JffiNativeGlobalInstance(
                globalsBufferAddr, globalIndex++, value, type, mutability);
    }

    public static Memory createMemory(MemoryLimits limits) {
        return new JffiNativeMemory(limits);
    }

    public Machine compile(Instance instance) {
        // Reset for reuse across multiple instances
        int importGlobalCount =
                (int)
                        module.importSection().stream()
                                .filter(
                                        i ->
                                                i.importType()
                                                        == com.dylibso.chicory.wasm.types
                                                                .ExternalType.GLOBAL)
                                .count();
        this.globalIndex = importGlobalCount;
        this.nativeMachine =
                new JffiNativeMachine(
                        instance,
                        nativeTables,
                        globalsBufferAddr,
                        precompiledCode,
                        compilerFunction);
        return nativeMachine;
    }

    @Override
    public void requestInterrupt() {
        if (nativeMachine != null) {
            nativeMachine.requestInterrupt();
        }
    }

    @Override
    public void clearInterrupt() {
        if (nativeMachine != null) {
            nativeMachine.clearInterrupt();
        }
    }

    @Override
    public void close() {
        if (nativeMachine != null) {
            nativeMachine.close();
        }
        for (JffiNativeTable table : nativeTables) {
            table.free();
        }
        nativeTables.clear();
        if (globalsBufferAddr != 0) {
            MEM.freeMemory(globalsBufferAddr);
            globalsBufferAddr = 0;
        }
    }

    public static final class Builder implements RedlineInstance.Builder {

        private final WasmModule module;
        private byte[][] precompiledCode;
        private Function<WasmModule, byte[][]> compilerFunction;
        private ImportValues importValues;
        private MemoryLimits memoryLimits;
        private boolean start = true;
        private boolean initialize = true;

        Builder(WasmModule module) {
            this.module = module;
        }

        @Override
        public Builder withPrecompiledCode(byte[][] precompiledCode) {
            this.precompiledCode = precompiledCode;
            return this;
        }

        public Builder withCompilerFunction(Function<WasmModule, byte[][]> compilerFunction) {
            this.compilerFunction = compilerFunction;
            return this;
        }

        @Override
        public Builder withImportValues(ImportValues importValues) {
            this.importValues = importValues;
            return this;
        }

        @Override
        public Builder withMemoryLimits(MemoryLimits limits) {
            this.memoryLimits = limits;
            return this;
        }

        @Override
        public Builder withStart(boolean start) {
            this.start = start;
            return this;
        }

        @Override
        public Builder withInitialize(boolean init) {
            this.initialize = init;
            return this;
        }

        @Override
        public RedlineInstance build() {
            var factory = new JffiNativeMachineFactory(module, precompiledCode, compilerFunction);
            var instanceBuilder =
                    Instance.builder(module)
                            .withMachineFactory(factory::compile)
                            .withTableFactory(factory::createTable)
                            .withGlobalFactory(factory::createGlobal)
                            .withMemoryFactory(JffiNativeMachineFactory::createMemory)
                            .withStart(start)
                            .withInitialize(initialize);
            if (importValues != null) {
                instanceBuilder.withImportValues(importValues);
            }
            if (memoryLimits != null) {
                instanceBuilder.withMemoryLimits(memoryLimits);
            }
            return new RedlineInstance(instanceBuilder.build(), factory);
        }
    }
}
