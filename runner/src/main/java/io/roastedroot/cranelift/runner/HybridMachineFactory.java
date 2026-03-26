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
import io.roastedroot.cranelift.runner.internal.HybridMachine;
import io.roastedroot.cranelift.runner.internal.NativeMachine;
import java.util.function.Function;

/**
 * Factory that creates a {@link HybridMachine} combining a Cranelift native
 * machine with a Chicory bytecode machine.
 *
 * <p>All functions are compiled by both compilers. The {@code isNativeFunction}
 * array determines which compiled version is used for each function at the
 * entry-point dispatch level. Once inside a machine, internal calls stay
 * within that machine.
 *
 * <p>Usage:
 * <pre>
 * var factory = new HybridMachineFactory(module, nativeCode, bytecodeFactory, isNative);
 * Instance.builder(module)
 *     .withMachineFactory(factory::compile)
 *     .withTableFactory(factory::createTable)
 *     .withGlobalFactory(factory::createGlobal)
 *     .withMemoryFactory(HybridMachineFactory::createMemory)
 *     .build();
 * </pre>
 */
public final class HybridMachineFactory implements AutoCloseable {

    private final NativeMachineFactory nativeFactory;
    private final Function<Instance, Machine> bytecodeFactory;
    private final boolean[] isNativeFunction;
    private final int numImports;

    public HybridMachineFactory(
            WasmModule module,
            byte[][] nativeCode,
            Function<Instance, Machine> bytecodeFactory,
            boolean[] isNativeFunction) {
        this.nativeFactory = new NativeMachineFactory(module, nativeCode);
        this.bytecodeFactory = bytecodeFactory;
        this.isNativeFunction = isNativeFunction;
        this.numImports =
                (int)
                        module.importSection().stream()
                                .filter(
                                        i ->
                                                i.importType()
                                                        == com.dylibso.chicory.wasm.types
                                                                .ExternalType.FUNCTION)
                                .count();
    }

    public Machine compile(Instance instance) {
        NativeMachine nativeMachine = (NativeMachine) nativeFactory.compile(instance);
        Machine bytecodeMachine = bytecodeFactory.apply(instance);
        return new HybridMachine(nativeMachine, bytecodeMachine, isNativeFunction, numImports);
    }

    public TableInstance createTable(Table table, int initValue) {
        return nativeFactory.createTable(table, initValue);
    }

    public GlobalInstance createGlobal(
            long value, long highValue, ValType type, MutabilityType mutability) {
        return nativeFactory.createGlobal(value, highValue, type, mutability);
    }

    public static Memory createMemory(MemoryLimits limits) {
        return NativeMachineFactory.createMemory(limits);
    }

    @Override
    public void close() {
        nativeFactory.close();
    }
}
