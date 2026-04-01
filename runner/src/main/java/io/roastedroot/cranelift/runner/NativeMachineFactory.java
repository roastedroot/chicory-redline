package io.roastedroot.cranelift.runner;

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
import io.roastedroot.cranelift.runner.internal.NativeGlobalInstance;
import io.roastedroot.cranelift.runner.internal.NativeMachine;
import io.roastedroot.cranelift.runner.internal.NativeMemory;
import io.roastedroot.cranelift.runner.internal.NativeTable;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.List;

/**
 * Public API for the Cranelift native compiler.
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
public final class NativeMachineFactory implements AutoCloseable {

    private final Arena arena = Arena.ofShared();
    private final WasmModule module;
    private final byte[][] precompiledCode;
    private final boolean runtimeCompilation;
    private final List<NativeTable> nativeTables = new ArrayList<>();
    private MemorySegment globalsBuffer;
    private int globalIndex;
    private NativeMachine nativeMachine;

    private NativeMachineFactory(
            WasmModule module, byte[][] precompiledCode, boolean runtimeCompilation) {
        this.module = module;
        this.precompiledCode = precompiledCode;
        this.runtimeCompilation = runtimeCompilation;

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

    /**
     * Returns a new {@link Builder} for the given module with precompiled
     * native code (e.g. from the Maven plugin).
     */
    public static Builder builder(WasmModule module, byte[][] precompiledCode) {
        return new Builder(module, precompiledCode);
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
                        runtimeCompilation);
        return nativeMachine;
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
     *         .withImportValues(imports)
     *         .build()) {
     *     ni.instance().export("func").apply();
     * }
     * </pre>
     */
    public static final class Builder {

        private final WasmModule module;
        private final byte[][] precompiledCode;
        private final boolean runtimeCompilation;
        private ImportValues importValues;
        private MemoryLimits memoryLimits;
        private boolean start = true;
        private boolean initialize = true;

        Builder(WasmModule module) {
            this.module = module;
            this.precompiledCode = null;
            this.runtimeCompilation = true;
        }

        Builder(WasmModule module, byte[][] precompiledCode) {
            this.module = module;
            this.precompiledCode = precompiledCode;
            this.runtimeCompilation = false;
        }

        public Builder withImportValues(ImportValues importValues) {
            this.importValues = importValues;
            return this;
        }

        public Builder withMemoryLimits(MemoryLimits limits) {
            this.memoryLimits = limits;
            return this;
        }

        public Builder withStart(boolean start) {
            this.start = start;
            return this;
        }

        public Builder withInitialize(boolean init) {
            this.initialize = init;
            return this;
        }

        public NativeInstance build() {
            var factory = new NativeMachineFactory(module, precompiledCode, runtimeCompilation);
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
            return new NativeInstance(instanceBuilder.build(), factory);
        }
    }
}
