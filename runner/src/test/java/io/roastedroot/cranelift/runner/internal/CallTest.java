package io.roastedroot.cranelift.runner.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.dylibso.chicory.runtime.ImportFunction;
import com.dylibso.chicory.runtime.ImportValues;
import com.dylibso.chicory.testing.NativeInstanceBuilder;
import com.dylibso.chicory.tools.wasm.Wat2Wasm;
import com.dylibso.chicory.wasm.ChicoryException;
import com.dylibso.chicory.wasm.Parser;
import com.dylibso.chicory.wasm.types.FunctionType;
import com.dylibso.chicory.wasm.types.ValType;
import org.junit.jupiter.api.Test;

public class CallTest {

    @Test
    public void shouldCallInternalFunction() {
        var wat =
                "(module"
                        + "  (func $add (param $a i32) (param $b i32) (result i32)"
                        + "    (i32.add (local.get $a) (local.get $b))"
                        + "  )"
                        + "  (func (export \"call_add\") (param $x i32) (param $y i32) (result i32)"
                        + "    (call $add (local.get $x) (local.get $y))"
                        + "  )"
                        + ")";

        var module = Parser.parse(Wat2Wasm.parse(wat));
        var instance = NativeInstanceBuilder.builder(module).build();

        var callAdd = instance.export("call_add");
        long[] result = callAdd.apply(17, 25);
        assertEquals(42L, result[0]);
    }

    @Test
    public void shouldCallImportFunction() {
        var wat =
                "(module"
                        + "  (import \"env\" \"double\" (func $double (param i32) (result i32)))"
                        + "  (func (export \"call_double\") (param $x i32) (result i32)"
                        + "    (call $double (local.get $x))"
                        + "  )"
                        + ")";

        var module = Parser.parse(Wat2Wasm.parse(wat));
        var importFunc =
                new ImportFunction(
                        "env",
                        "double",
                        FunctionType.of(
                                java.util.List.of(ValType.I32), java.util.List.of(ValType.I32)),
                        (inst, args) -> new long[] {args[0] * 2});
        var instance =
                NativeInstanceBuilder.builder(module)
                        .withImportValues(ImportValues.builder().addFunction(importFunc).build())
                        .build();

        var callDouble = instance.export("call_double");
        long[] result = callDouble.apply(21);
        assertEquals(42L, result[0]);
    }

    @Test
    public void shouldCallVoidImportFunction() {
        var wat =
                "(module"
                        + "  (import \"env\" \"log\" (func $log (param i32)))"
                        + "  (func (export \"call_log\") (param $x i32)"
                        + "    (call $log (local.get $x))"
                        + "  )"
                        + ")";

        var logged = new long[1];
        var module = Parser.parse(Wat2Wasm.parse(wat));
        var importFunc =
                new ImportFunction(
                        "env",
                        "log",
                        FunctionType.of(java.util.List.of(ValType.I32), java.util.List.of()),
                        (inst, args) -> {
                            logged[0] = args[0];
                            return new long[0];
                        });
        var instance =
                NativeInstanceBuilder.builder(module)
                        .withImportValues(ImportValues.builder().addFunction(importFunc).build())
                        .build();

        var callLog = instance.export("call_log");
        callLog.apply(42);
        assertEquals(42L, logged[0]);
    }

    @Test
    public void shouldCallIndirectBasic() {
        var wat =
                "(module  (type $t (func (param i32 i32) (result i32)))  (func $add (param $a i32)"
                    + " (param $b i32) (result i32)    (i32.add (local.get $a) (local.get $b))  ) "
                    + " (table 1 funcref)  (elem (i32.const 0) $add)  (func (export \"test\")"
                    + " (param $a i32) (param $b i32) (result i32)    (call_indirect (type $t)"
                    + " (local.get $a) (local.get $b) (i32.const 0))  ))";

        var module = Parser.parse(Wat2Wasm.parse(wat));
        var instance = NativeInstanceBuilder.builder(module).build();

        var test = instance.export("test");
        long[] result = test.apply(17, 25);
        assertEquals(42L, result[0]);
    }

    @Test
    public void shouldTrapOnUndefinedCallIndirect() {
        var wat =
                "(module  (type $t (func (param i32 i32) (result i32)))  (func $add (param $a i32)"
                    + " (param $b i32) (result i32)    (i32.add (local.get $a) (local.get $b))  ) "
                    + " (table 1 funcref)  (elem (i32.const 0) $add)  (func (export \"test\")"
                    + " (param $a i32) (param $b i32) (result i32)    (call_indirect (type $t)"
                    + " (local.get $a) (local.get $b) (i32.const 1))  ))";

        var module = Parser.parse(Wat2Wasm.parse(wat));
        var instance = NativeInstanceBuilder.builder(module).build();

        var test = instance.export("test");
        assertThrows(ChicoryException.class, () -> test.apply(17, 25));
    }

    @Test
    public void shouldCallNativeToNativeChain() {
        var wat =
                "(module"
                        + "  (func $double (param $x i32) (result i32)"
                        + "    (i32.mul (local.get $x) (i32.const 2))"
                        + "  )"
                        + "  (func $quadruple (param $x i32) (result i32)"
                        + "    (call $double (call $double (local.get $x)))"
                        + "  )"
                        + "  (func (export \"test\") (param $x i32) (result i32)"
                        + "    (call $quadruple (local.get $x))"
                        + "  )"
                        + ")";

        var module = Parser.parse(Wat2Wasm.parse(wat));
        var instance = NativeInstanceBuilder.builder(module).build();

        var test = instance.export("test");
        long[] result = test.apply(10);
        assertEquals(40L, result[0]);
    }
}
