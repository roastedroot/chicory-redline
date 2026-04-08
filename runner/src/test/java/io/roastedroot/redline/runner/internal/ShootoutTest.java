package io.roastedroot.redline.runner.internal;

import com.dylibso.chicory.runtime.HostFunction;
import com.dylibso.chicory.runtime.ImportValues;
import com.dylibso.chicory.testing.NativeInstanceBuilder;
import com.dylibso.chicory.wasi.WasiOptions;
import com.dylibso.chicory.wasi.WasiPreview1;
import com.dylibso.chicory.wasm.Parser;
import com.dylibso.chicory.wasm.types.FunctionType;
import java.util.List;
import org.junit.jupiter.api.Test;

public class ShootoutTest {

    private void runShootout(String resourceName) {
        var module =
                Parser.parse(ShootoutTest.class.getClassLoader().getResourceAsStream(resourceName));

        var wasi = WasiPreview1.builder().withOptions(WasiOptions.builder().build()).build();

        var benchStart =
                new HostFunction(
                        "bench",
                        "start",
                        FunctionType.of(List.of(), List.of()),
                        (inst, vals) -> null);
        var benchEnd =
                new HostFunction(
                        "bench",
                        "end",
                        FunctionType.of(List.of(), List.of()),
                        (inst, vals) -> null);

        var imports =
                ImportValues.builder()
                        .addFunction(wasi.toHostFunctions())
                        .addFunction(benchStart, benchEnd)
                        .build();

        var instance =
                NativeInstanceBuilder.builder(module)
                        .withImportValues(imports)
                        .withStart(false)
                        .build();

        var startFn = instance.export("_start");
        try {
            startFn.apply();
        } catch (RuntimeException e) {
            // proc_exit(0) may throw
        }

        var runFn = instance.export("run");
        runFn.apply();
    }

    @Test
    public void shootoutHeapsort() {
        runShootout("shootout-heapsort.wasm");
    }

    @Test
    public void shootoutSeqhash() {
        runShootout("shootout-seqhash.wasm");
    }

    @Test
    public void shootoutSwitch() {
        runShootout("shootout-switch.wasm");
    }
}
