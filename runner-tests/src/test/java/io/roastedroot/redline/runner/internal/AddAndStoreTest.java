package io.roastedroot.redline.runner.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.dylibso.chicory.testing.NativeInstanceBuilder;
import com.dylibso.chicory.tools.wasm.Wat2Wasm;
import com.dylibso.chicory.wasm.Parser;
import org.junit.jupiter.api.Test;

public class AddAndStoreTest {

    @Test
    public void shouldAddAndStoreToMemory() {
        var wat =
                "(module"
                        + "  (memory (export \"memory\") 1)"
                        + "  (func (export \"add_and_store\")"
                        + "    (param $a i32) (param $b i32) (param $addr i32) (result i32)"
                        + "    (local $sum i32)"
                        + "    (local.set $sum (i32.add (local.get $a) (local.get $b)))"
                        + "    (i32.store (local.get $addr) (local.get $sum))"
                        + "    (local.get $sum)"
                        + "  )"
                        + ")";

        var module = Parser.parse(Wat2Wasm.parse(wat));
        var instance = NativeInstanceBuilder.builder(module).build();

        var addAndStore = instance.export("add_and_store");
        long[] result = addAndStore.apply(17, 25, 0);

        assertEquals(42L, result[0]);

        // Verify the value was actually written to memory
        assertEquals(42, instance.memory().readInt(0));
    }
}
