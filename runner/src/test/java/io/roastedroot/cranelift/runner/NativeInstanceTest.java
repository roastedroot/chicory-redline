package io.roastedroot.cranelift.runner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dylibso.chicory.runtime.Instance;
import com.dylibso.chicory.tools.wasm.Wat2Wasm;
import com.dylibso.chicory.wasm.Parser;
import com.dylibso.chicory.wasm.WasmModule;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

@EnabledOnOs(OS.LINUX)
public class NativeInstanceTest {

    private static final String WAT =
            "(module"
                    + "  (memory 1 4096)"
                    + "  (func (export \"f\") (result i32) i32.const 42)"
                    + ")";

    @Test
    public void shouldReleaseResourcesOnClose() {
        var module = Parser.parse(Wat2Wasm.parse(WAT));

        long vmBefore = readVmSizeKb();

        for (int i = 0; i < 20; i++) {
            try (var ni = createNativeInstance(module)) {
                var f = ni.instance().export("f");
                assertEquals(42L, f.apply()[0]);
            }
        }

        long vmAfter = readVmSizeKb();
        long growthMb = (vmAfter - vmBefore) / 1024;
        // 20 instances × ~256MB each = 5GB if leaked.
        // With close(), should stay near zero. Allow 768MB for JVM/compilation noise.
        assertTrue(
                growthMb < 768,
                "Virtual memory grew by " + growthMb + "MB after closing — resources leaked");
    }

    private static NativeInstance createNativeInstance(WasmModule module) {
        var factory = new NativeMachineFactory(module);
        var instance =
                Instance.builder(module)
                        .withMachineFactory(factory::compile)
                        .withTableFactory(factory::createTable)
                        .withGlobalFactory(factory::createGlobal)
                        .withMemoryFactory(NativeMachineFactory::createMemory)
                        .build();
        return new NativeInstance(instance, factory);
    }

    private static long readVmSizeKb() {
        try {
            return Files.readAllLines(Path.of("/proc/self/status")).stream()
                    .filter(l -> l.startsWith("VmSize:"))
                    .mapToLong(l -> Long.parseLong(l.replaceAll("[^0-9]", "")))
                    .findFirst()
                    .orElse(0);
        } catch (IOException e) {
            return 0;
        }
    }
}
