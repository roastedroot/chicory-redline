package io.roastedroot.cranelift.runner.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.dylibso.chicory.runtime.Instance;
import com.dylibso.chicory.tools.wasm.Wat2Wasm;
import com.dylibso.chicory.wasm.Parser;
import io.roastedroot.cranelift.runner.NativeMachineFactory;
import org.junit.jupiter.api.Test;

public class FactoryReuseTest {

    @Test
    public void shouldReuseFactoryWithGlobals() {
        var wat =
                "(module"
                        + "  (global $g (mut i32) (i32.const 0))"
                        + "  (func (export \"inc\") (result i32)"
                        + "    (global.set $g (i32.add (global.get $g) (i32.const 1)))"
                        + "    (global.get $g)"
                        + "  )"
                        + ")";

        var module = Parser.parse(Wat2Wasm.parse(wat));
        var factory = new NativeMachineFactory(module);

        for (int i = 0; i < 3; i++) {
            var instance =
                    Instance.builder(module)
                            .withMachineFactory(factory::compile)
                            .withTableFactory(factory::createTable)
                            .withGlobalFactory(factory::createGlobal)
                            .withMemoryFactory(NativeMachineFactory::createMemory)
                            .build();

            // Each instance should start fresh — global starts at 0
            var inc = instance.export("inc");
            assertEquals(1L, inc.apply()[0], "iteration " + i + ": first inc");
            assertEquals(2L, inc.apply()[0], "iteration " + i + ": second inc");
        }

        factory.close();
    }

    @Test
    public void shouldReuseFactoryWithTables() {
        var wat =
                "(module"
                        + "  (type $t (func (result i32)))"
                        + "  (func $f1 (result i32) (i32.const 42))"
                        + "  (func $f2 (result i32) (i32.const 99))"
                        + "  (table 2 funcref)"
                        + "  (elem (i32.const 0) $f1 $f2)"
                        + "  (func (export \"call_table\") (param i32) (result i32)"
                        + "    (call_indirect (type $t) (local.get 0))"
                        + "  )"
                        + ")";

        var module = Parser.parse(Wat2Wasm.parse(wat));
        var factory = new NativeMachineFactory(module);

        for (int i = 0; i < 3; i++) {
            var instance =
                    Instance.builder(module)
                            .withMachineFactory(factory::compile)
                            .withTableFactory(factory::createTable)
                            .withGlobalFactory(factory::createGlobal)
                            .withMemoryFactory(NativeMachineFactory::createMemory)
                            .build();

            var callTable = instance.export("call_table");
            assertEquals(42L, callTable.apply(0)[0], "iteration " + i + ": f1");
            assertEquals(99L, callTable.apply(1)[0], "iteration " + i + ": f2");
        }

        factory.close();
    }
}
