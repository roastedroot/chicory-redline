package io.roastedroot.cranelift.runner.internal;

import static org.junit.jupiter.api.Assertions.assertThrows;

import com.dylibso.chicory.runtime.ImportFunction;
import com.dylibso.chicory.runtime.ImportValues;
import com.dylibso.chicory.testing.NativeInstanceBuilder;
import com.dylibso.chicory.tools.wasm.Wat2Wasm;
import com.dylibso.chicory.wasm.Parser;
import com.dylibso.chicory.wasm.types.FunctionType;
import com.dylibso.chicory.wasm.types.ValType;
import org.junit.jupiter.api.Test;

public class HostExceptionTest {

    /**
     * Custom exception to simulate WasiExitException or similar.
     */
    static class ExitException extends RuntimeException {
        final int exitCode;

        ExitException(int exitCode) {
            super("exit with code " + exitCode);
            this.exitCode = exitCode;
        }
    }

    /**
     * When an imported function throws and native code then hits unreachable,
     * the original exception must propagate — not be masked by a trap.
     */
    @Test
    public void shouldPropagateHostExceptionOverTrap() {
        // Module: calls imported "exit" then hits unreachable
        var wat =
                "(module"
                        + "  (import \"env\" \"exit\" (func $exit (param i32)))"
                        + "  (func (export \"run\")"
                        + "    (call $exit (i32.const 42))"
                        + "    unreachable"
                        + "  )"
                        + ")";

        var module = Parser.parse(Wat2Wasm.parse(wat));
        var importFunc =
                new ImportFunction(
                        "env",
                        "exit",
                        FunctionType.of(java.util.List.of(ValType.I32), java.util.List.of()),
                        (inst, args) -> {
                            throw new ExitException((int) args[0]);
                        });
        var instance =
                NativeInstanceBuilder.builder(module)
                        .withImportValues(ImportValues.builder().addFunction(importFunc).build())
                        .build();

        var run = instance.export("run");

        // Must throw ExitException, not ChicoryException("unreachable")
        var ex = assertThrows(ExitException.class, () -> run.apply());
        assert ex.exitCode == 42;
    }
}
