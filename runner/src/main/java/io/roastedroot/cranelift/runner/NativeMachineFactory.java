package io.roastedroot.cranelift.runner;

import com.dylibso.chicory.runtime.GlobalInstance;
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
 * var factory = new NativeMachineFactory(module);
 * Instance.builder(module)
 *     .withMachineFactory(factory::compile)
 *     .withTableFactory(factory::createTable)
 *     .withGlobalFactory(factory::createGlobal)
 *     .withMemoryFactory(NativeMachineFactory::createMemory)
 *     .build();
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
    private final List<NativeTable> nativeTables = new ArrayList<>();
    private MemorySegment globalsBuffer;
    private int globalIndex;
    private NativeMachine nativeMachine;

    public NativeMachineFactory(WasmModule module) {
        this(module, null);
    }

    public NativeMachineFactory(WasmModule module, byte[][] precompiledCode) {
        this.module = module;
        this.precompiledCode = precompiledCode;

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
                new NativeMachine(instance, arena, nativeTables, globalsBuffer, precompiledCode);
        return nativeMachine;
    }

    @Override
    public void close() {
        if (nativeMachine != null) {
            nativeMachine.close();
        }
    }
}
