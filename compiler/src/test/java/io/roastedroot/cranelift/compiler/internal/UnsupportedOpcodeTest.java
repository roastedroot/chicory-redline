package io.roastedroot.cranelift.compiler.internal;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dylibso.chicory.tools.wasm.Wat2Wasm;
import com.dylibso.chicory.wasm.ChicoryException;
import com.dylibso.chicory.wasm.Parser;
import org.junit.jupiter.api.Test;

public class UnsupportedOpcodeTest {

    @Test
    public void shouldCompileEmptyFunction() {
        var wat = "(module" + "  (func (export \"noop\") (param i32))" + ")";

        var module = Parser.parse(Wat2Wasm.parse(wat));
        var compiler = new NativeCompiler("x86_64-unknown-linux-gnu", module);

        var result = compiler.compileAll();
        assertTrue(result.length == 1, "Should compile 1 function");
        assertTrue(result[0] != null && result[0].length > 0, "Should produce non-empty code");
    }

    @Test
    public void shouldCompileIfWithLocalSetInOneBranch() {
        // Reproducer for prism.wasm function 93: local.set inside if/end
        // where the local is used after the if block. The else branch
        // leaves the local at its default (0). This pattern crashed the
        // Cranelift bridge.
        var wat =
                "(module"
                        + "  (memory 1)"
                        + "  (func $realloc (param i32 i32) (result i32) i32.const 0)"
                        + "  (func (export \"test\") (param i32 i32 i32)"
                        + "    (local i32)"
                        + "    local.get 2"
                        + "    if"
                        + "      local.get 0"
                        + "      local.get 2"
                        + "      i32.const 2"
                        + "      i32.shl"
                        + "      call $realloc"
                        + "      local.set 3"
                        + "    end"
                        + "    local.get 1"
                        + "    local.get 2"
                        + "    i32.store offset=4"
                        + "    local.get 1"
                        + "    i32.const 0"
                        + "    i32.store"
                        + "    local.get 1"
                        + "    local.get 3"
                        + "    i32.store offset=8"
                        + "  )"
                        + ")";

        var module = Parser.parse(Wat2Wasm.parse(wat));
        var compiler = new NativeCompiler("x86_64-unknown-linux-gnu", module);

        var result = compiler.compileAll();
        assertTrue(result.length == 2, "Should compile 2 functions");
        assertTrue(result[1] != null && result[1].length > 0, "Should produce non-empty code");
    }

    @Test
    public void shouldCompileAtomicLoad() {
        var wat =
                "(module"
                        + "  (memory 1 1 shared)"
                        + "  (func (export \"atomic_load\") (param i32) (result i32)"
                        + "    (i32.atomic.load (local.get 0))"
                        + "  )"
                        + ")";

        var module = Parser.parse(Wat2Wasm.parse(wat));
        var compiler = new NativeCompiler("x86_64-unknown-linux-gnu", module);

        var result = compiler.compileAll();
        assertTrue(result.length == 1, "Should compile 1 function");
        assertTrue(result[0] != null && result[0].length > 0, "Should produce non-empty code");
    }

    @Test
    public void shouldCompileAtomicOpsAlongsideRegular() {
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
        var compiler = new NativeCompiler("x86_64-unknown-linux-gnu", module);

        var result = compiler.compileAll();
        assertTrue(result.length == 2, "Should compile 2 functions");
        assertTrue(result[0] != null && result[0].length > 0, "Should produce non-empty code");
        assertTrue(result[1] != null && result[1].length > 0, "Should produce non-empty code");
    }

    @Test
    public void shouldFailAtCompileTimeForSimdOpcode() {
        var wat =
                "(module"
                        + "  (memory 1)"
                        + "  (func (export \"simd_load\") (param i32) (result v128)"
                        + "    (v128.load (local.get 0))"
                        + "  )"
                        + ")";

        var module = Parser.parse(Wat2Wasm.parse(wat));
        var compiler = new NativeCompiler("x86_64-unknown-linux-gnu", module);

        var ex =
                assertThrows(
                        ChicoryException.class,
                        compiler::compileAll,
                        "SIMD opcodes should fail at compile time");
        assertTrue(
                ex.getCause() instanceof UnsupportedOperationException,
                "Root cause should be UnsupportedOperationException");
    }
}
