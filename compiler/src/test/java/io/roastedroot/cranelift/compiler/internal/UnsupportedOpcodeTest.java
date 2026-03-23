package io.roastedroot.cranelift.compiler.internal;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dylibso.chicory.tools.wasm.Wat2Wasm;
import com.dylibso.chicory.wasm.ChicoryException;
import com.dylibso.chicory.wasm.Parser;
import io.roastedroot.cranelift.compiler.NativeMachineFactory;
import org.junit.jupiter.api.Test;

public class UnsupportedOpcodeTest {

    @Test
    public void shouldFailAtCompileTimeForAtomicOpcode() {
        var wat =
                "(module"
                        + "  (memory 1 1 shared)"
                        + "  (func (export \"atomic_load\") (param i32) (result i32)"
                        + "    (i32.atomic.load (local.get 0))"
                        + "  )"
                        + ")";

        var module = Parser.parse(Wat2Wasm.parse(wat));
        var factory = new NativeMachineFactory(module);

        var ex =
                assertThrows(
                        ChicoryException.class,
                        () ->
                                com.dylibso.chicory.runtime.Instance.builder(module)
                                        .withMachineFactory(factory::compile)
                                        .withTableFactory(factory::createTable)
                                        .withGlobalFactory(factory::createGlobal)
                                        .withMemoryFactory(NativeMachineFactory::createMemory)
                                        .build());

        assertTrue(
                ex.getMessage().contains("I32_ATOMIC_LOAD")
                        || (ex.getCause() != null
                                && ex.getCause().getMessage().contains("I32_ATOMIC_LOAD")),
                "Exception should mention the unsupported opcode, got: " + ex.getMessage());
    }

    @Test
    public void shouldFailEvenIfOnlyOneFunction() {
        var wat =
                "(module"
                        + "  (memory 1 1 shared)"
                        + "  (func (export \"add\") (param i32 i32) (result i32)"
                        + "    (i32.add (local.get 0) (local.get 1))"
                        + "  )"
                        + "  (func (export \"atomic_load\") (param i32) (result i32)"
                        + "    (i32.atomic.load (local.get 0))"
                        + "  )"
                        + ")";

        var module = Parser.parse(Wat2Wasm.parse(wat));
        var factory = new NativeMachineFactory(module);

        var ex =
                assertThrows(
                        ChicoryException.class,
                        () ->
                                com.dylibso.chicory.runtime.Instance.builder(module)
                                        .withMachineFactory(factory::compile)
                                        .withTableFactory(factory::createTable)
                                        .withGlobalFactory(factory::createGlobal)
                                        .withMemoryFactory(NativeMachineFactory::createMemory)
                                        .build());

        assertTrue(
                ex.getMessage().contains("function 1")
                        || (ex.getCause() != null
                                && ex.getCause().getMessage().contains("function 1")),
                "Exception should mention the failing function index, got: " + ex.getMessage());
    }
}
