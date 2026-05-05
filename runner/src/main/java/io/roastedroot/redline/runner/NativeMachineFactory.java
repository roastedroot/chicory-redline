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
import io.roastedroot.redline.api.Interruptable;
import io.roastedroot.redline.api.RedlineInstance;
import io.roastedroot.redline.runner.internal.NativeGlobalInstance;
import io.roastedroot.redline.runner.internal.NativeMachine;
import io.roastedroot.redline.runner.internal.NativeMemory;
import io.roastedroot.redline.runner.internal.NativeTable;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Public API for the Redline native compiler.
 *
 * <p>Usage:
 * <pre>
 * try (var ni = NativeMachineFactory.builder(module).build()) {
 *     ni.instance().export("func").apply();
 * }
 * </pre>
 *
 * <p>The factory holds shared off-heap state (Arena, globals buffer) that is used
 * by both the table/global factories and the NativeMachine. This ensures tables
 * and globals are created directly in off-heap memory — no sync or reflection needed.
 */
public final class NativeMachineFactory implements AutoCloseable, Interruptable {

    private final Arena arena = Arena.ofShared();
    private final WasmModule module;
    private final byte[][] precompiledCode;
    private final Function<WasmModule, byte[][]> compilerFunction;
    private final List<NativeTable> nativeTables = new ArrayList<>();
    private MemorySegment globalsBuffer;
    private int globalIndex;
    private NativeMachine nativeMachine;

    private NativeMachineFactory(
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
        this.globalsBuffer =
                totalGlobals > 0 ? arena.allocate((long) totalGlobals * 8, 8) : MemorySegment.NULL;
        this.globalIndex = importGlobalCount; // module globals start after imports
    }

    /**
     * Returns a new {@link Builder} for the given module. The module will be
     * compiled at runtime via Cranelift.
     */
    public static Builder builder(WasmModule module) {
        return new Builder(module);
    }

    public TableInstance createTable(Table table, int initValue) {
        var nativeTable = new NativeTable(table, arena);
        nativeTables.add(nativeTable);
        return nativeTable;
    }

    public GlobalInstance createGlobal(
            long value, long highValue, ValType type, MutabilityType mutability) {
        var global =
                new NativeGlobalInstance(globalsBuffer, globalIndex++, value, type, mutability);
        return global;
    }

    public static Memory createMemory(MemoryLimits limits) {
        return new NativeMemory(limits);
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
        this.nativeTables.clear();
        this.nativeMachine =
                new NativeMachine(
                        instance,
                        arena,
                        nativeTables,
                        globalsBuffer,
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
        try {
            arena.close();
        } catch (IllegalStateException e) {
            // ignore — may already be closed by NativeMachine's CleanupAction
        }
    }

    /**
     * Fluent builder for creating a {@link NativeInstance}.
     *
     * <pre>
     * try (var ni = NativeMachineFactory.builder(module)
     *         .withPrecompiledCode(code)
     *         .withImportValues(imports)
     *         .build()) {
     *     ni.instance().export("func").apply();
     * }
     * </pre>
     */
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
            var factory = new NativeMachineFactory(module, precompiledCode, compilerFunction);
            var instanceBuilder =
                    Instance.builder(module)
                            .withMachineFactory(factory::compile)
                            .withTableFactory(factory::createTable)
                            .withGlobalFactory(factory::createGlobal)
                            .withMemoryFactory(NativeMachineFactory::createMemory)
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
