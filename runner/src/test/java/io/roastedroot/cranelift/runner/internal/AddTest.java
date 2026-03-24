package io.roastedroot.cranelift.runner.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.dylibso.chicory.testing.NativeInstanceBuilder;
import com.dylibso.chicory.tools.wasm.Wat2Wasm;
import com.dylibso.chicory.wasm.Parser;
import org.junit.jupiter.api.Test;

public class AddTest {

    @Test
    public void shouldAddTwoNumbers() {
        var wat =
                "(module"
                        + "  (func (export \"add\") (param $a i32) (param $b i32) (result i32)"
                        + "    (i32.add (local.get $a) (local.get $b))"
                        + "  )"
                        + ")";

        var module = Parser.parse(Wat2Wasm.parse(wat));
        var instance = NativeInstanceBuilder.builder(module).build();

        var add = instance.export("add");
        long[] result = add.apply(17, 25);
        assertEquals(42L, result[0]);
    }
}
