package io.roastedroot.cranelift.runner.internal;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dylibso.chicory.tools.wasm.Wat2Wasm;
import com.dylibso.chicory.wasm.Parser;
import com.dylibso.chicory.wasm.types.MemoryLimits;
import io.roastedroot.cranelift.api.CraneliftTarget;
import io.roastedroot.cranelift.compiler.internal.NativeCompiler;
import io.roastedroot.cranelift.runner.NativeMachineFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

@EnabledOnOs(OS.LINUX)
public class NativeMemoryLeakTest {

    @Test
    public void shouldNotLeakVirtualMemoryOnClose() {
        long vmBefore = readVmSizeKb();

        for (int i = 0; i < 50; i++) {
            var mem = new NativeMemory(new MemoryLimits(1, 4096));
            mem.close();
        }

        long vmAfter = readVmSizeKb();
        long growthMb = (vmAfter - vmBefore) / 1024;
        assertTrue(growthMb < 512, "Virtual memory grew by " + growthMb + "MB — likely mmap leak");
    }

    @Test
    public void shouldNotLeakVirtualMemoryViaFactory() {
        var wat =
                "(module"
                        + "  (memory 1 4096)"
                        + "  (func (export \"f\") (result i32) i32.const 42)"
                        + ")";
        var module = Parser.parse(Wat2Wasm.parse(wat));

        long vmBefore = readVmSizeKb();

        for (int i = 0; i < 20; i++) {
            try (var ni =
                    NativeMachineFactory.builder(module)
                            .withCompilerFunction(
                                    m ->
                                            new NativeCompiler(CraneliftTarget.detectHost(), m)
                                                    .compileAll())
                            .build()) {
                // just create and close
            }
        }

        long vmAfter = readVmSizeKb();
        long growthMb = (vmAfter - vmBefore) / 1024;
        // 20 instances × 1 page each = 1.25MB if leaked. Allow 512MB for JVM noise.
        assertTrue(growthMb < 512, "Virtual memory grew by " + growthMb + "MB — likely mmap leak");
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
