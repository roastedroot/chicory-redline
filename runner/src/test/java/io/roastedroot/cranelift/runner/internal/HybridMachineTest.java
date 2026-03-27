package io.roastedroot.cranelift.runner.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.dylibso.chicory.runtime.InterpreterMachine;
import com.dylibso.chicory.tools.wasm.Wat2Wasm;
import com.dylibso.chicory.wasm.Parser;
import com.dylibso.chicory.wasm.WasmModule;
import com.dylibso.chicory.wasm.types.ExternalType;
import io.roastedroot.cranelift.runner.HybridMachineFactory;
import io.roastedroot.cranelift.runner.NativeMachineFactory;
import org.junit.jupiter.api.Test;

/**
 * Smoke tests for cross-boundary dispatch in the hybrid machine.
 * Uses InterpreterMachine as the bytecode side so we can control
 * exactly which functions run native vs interpreted.
 */
public class HybridMachineTest {

    /**
     * Helper: build a hybrid instance where only functions in {@code nativeFuncIds}
     * run on native and everything else runs on the interpreter.
     */
    private static com.dylibso.chicory.runtime.Instance buildHybrid(
            WasmModule module, int... nativeFuncIds) {
        int numImports =
                (int)
                        module.importSection().stream()
                                .filter(i -> i.importType() == ExternalType.FUNCTION)
                                .count();
        int totalFuncs = numImports + module.codeSection().functionBodyCount();
        boolean[] isNative = new boolean[totalFuncs];
        for (int id : nativeFuncIds) {
            isNative[id] = true;
        }

        // Compile only native-selected functions
        var compiler =
                new io.roastedroot.cranelift.compiler.internal.NativeCompiler(
                        io.roastedroot.cranelift.compiler.CraneliftTarget.detectHost(), module);
        byte[][] code = compiler.compileAll(isNative);

        var factory = new HybridMachineFactory(module, code, InterpreterMachine::new, isNative);
        return com.dylibso.chicory.runtime.Instance.builder(module)
                .withMachineFactory(factory::compile)
                .withTableFactory(factory::createTable)
                .withGlobalFactory(factory::createGlobal)
                .withMemoryFactory(NativeMachineFactory::createMemory)
                .build();
    }

    // --- Test 1: native function calls bytecode function via bridge stub ---

    @Test
    public void nativeCallsBytecodeViaDirectCall() {
        // func 0 (small): add two numbers
        // func 1 (big/native): calls func 0 and doubles the result
        // Entry point is func 1 (native). Internal call to func 0 goes through
        // bridge stub → importDispatchDirect → HybridMachine → interpreter.
        var wat =
                """
                (module
                  (func $add (param i32) (param i32) (result i32)
                    (i32.add (local.get 0) (local.get 1))
                  )
                  (func (export "test") (param i32) (param i32) (result i32)
                    (i32.mul
                      (call $add (local.get 0) (local.get 1))
                      (i32.const 2))
                  )
                )
                """;
        var module = Parser.parse(Wat2Wasm.parse(wat));
        // func 0 = $add (import count 0 + body index 0), func 1 = test (0 + 1)
        // Only func 1 is native; func 0 runs on interpreter
        var instance = buildHybrid(module, 1);

        var result = instance.export("test").apply(10, 11);
        assertEquals(42L, result[0]);
    }

    // --- Test 2: bytecode entry calls native function ---

    @Test
    public void bytecodeEntryCallsNative() {
        // func 0 (native): multiply by 3
        // func 1 (bytecode, entry): calls func 0
        // HybridMachine dispatches func 1 to interpreter. Interpreter calls
        // func 0 via INVOKESTATIC (stays interpreter — both compile all funcs).
        // This verifies entry-point dispatch routes correctly.
        var wat =
                """
                (module
                  (func $triple (param i32) (result i32)
                    (i32.mul (local.get 0) (i32.const 3))
                  )
                  (func (export "test") (param i32) (result i32)
                    (call $triple (local.get 0))
                  )
                )
                """;
        var module = Parser.parse(Wat2Wasm.parse(wat));
        // func 0 = native, func 1 = bytecode (entry point)
        var instance = buildHybrid(module, 0);

        var result = instance.export("test").apply(14);
        assertEquals(42L, result[0]);
    }

    // --- Test 3: cross-boundary with memory.grow (the exact bug we fixed) ---

    @Test
    public void crossBoundaryMemoryGrow() {
        // func 0 (bytecode): calls memory.grow, returns old page count
        // func 1 (native, entry): calls func 0, then stores a value in the
        //   newly grown page and loads it back.
        // This is exactly the scenario that caused the OOB trap before the fix:
        // func 0 runs through bridge stub → interpreter → memory.grow updates
        // NativeMemory.nPages but not ctxBuffer. After returning to native code,
        // the store into the new page would OOB with stale ctxBuffer.MEMORY_PAGES.
        var wat =
                """
                (module
                  (memory (export "mem") 1 10)
                  (func $grow (result i32)
                    (memory.grow (i32.const 1))
                  )
                  (func (export "test") (result i32)
                    (drop (call $grow))
                    ;; Store 42 at byte 65536 (first byte of the new page)
                    (i32.store (i32.const 65536) (i32.const 42))
                    ;; Load it back
                    (i32.load (i32.const 65536))
                  )
                )
                """;
        var module = Parser.parse(Wat2Wasm.parse(wat));
        // func 0 ($grow) = bytecode, func 1 (test) = native
        var instance = buildHybrid(module, 1);

        var result = instance.export("test").apply();
        assertEquals(42L, result[0]);
    }

    // --- Test 4: call_indirect across boundary ---

    @Test
    public void callIndirectCrossBoundary() {
        // func 0 (bytecode): add
        // func 1 (native, entry): uses call_indirect to call func 0 via table
        // call_indirect goes through trampoline → NativeMachine.call() →
        // HybridMachine.call() → interpreter (since func 0 is bytecode).
        var wat =
                """
                (module
                  (type $binop (func (param i32 i32) (result i32)))
                  (func $add (param i32) (param i32) (result i32)
                    (i32.add (local.get 0) (local.get 1))
                  )
                  (table 1 funcref)
                  (elem (i32.const 0) $add)
                  (func (export "test") (param i32) (param i32) (result i32)
                    (call_indirect (type $binop)
                      (local.get 0) (local.get 1) (i32.const 0))
                  )
                )
                """;
        var module = Parser.parse(Wat2Wasm.parse(wat));
        // func 0 ($add) = bytecode, func 1 (test) = native
        var instance = buildHybrid(module, 1);

        var result = instance.export("test").apply(17, 25);
        assertEquals(42L, result[0]);
    }

    // --- Test 5: chain of cross-boundary calls ---

    @Test
    public void chainedCrossBoundaryCalls() {
        // func 0 (bytecode): increment by 1
        // func 1 (native): calls func 0 twice (increments by 2)
        // func 2 (bytecode): calls func 1 (crosses back to native)
        // func 3 (native, entry): calls func 2 (crosses to bytecode)
        // Call chain: native → bytecode → native → bytecode → return
        var wat =
                """
                (module
                  (func $inc (param i32) (result i32)
                    (i32.add (local.get 0) (i32.const 1))
                  )
                  (func $inc2 (param i32) (result i32)
                    (call $inc (call $inc (local.get 0)))
                  )
                  (func $inc2_wrapper (param i32) (result i32)
                    (call $inc2 (local.get 0))
                  )
                  (func (export "test") (param i32) (result i32)
                    (call $inc2_wrapper (local.get 0))
                  )
                )
                """;
        var module = Parser.parse(Wat2Wasm.parse(wat));
        // func 0 ($inc) = bytecode, func 1 ($inc2) = native,
        // func 2 ($inc2_wrapper) = bytecode, func 3 (test) = native
        var instance = buildHybrid(module, 1, 3);

        var result = instance.export("test").apply(40);
        assertEquals(42L, result[0]);
    }
}
