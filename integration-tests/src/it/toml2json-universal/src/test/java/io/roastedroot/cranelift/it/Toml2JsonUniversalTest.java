package io.roastedroot.cranelift.it;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dylibso.chicory.runtime.Instance;
import io.roastedroot.cranelift4j.UniversalInstance;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class Toml2JsonUniversalTest {

    private static final String SAMPLE_TOML = "[package]\nname = \"test\"\nversion = \"1.0.0\"\n";

    @Test
    void testBuilderAutoDetect() {
        try (var ci = Toml2JsonModule.builder().build()) {
            var result = convert(ci.instance(), SAMPLE_TOML);
            assertNotNull(result);
            assertTrue(
                    result.contains("\"package\""), "Expected JSON key 'package', got: " + result);
        }
    }

    @Test
    void testChicoryFallbackWithNullNativeCode() {
        try (var ci =
                UniversalInstance.builder(Toml2JsonModule.load())
                        .withChicoryFallback(Toml2JsonModule::create)
                        .build()) {
            var result = convert(ci.instance(), SAMPLE_TOML);
            assertNotNull(result);
            assertTrue(
                    result.contains("\"package\""), "Expected JSON key 'package', got: " + result);
        }
    }

    @Test
    void testChicoryCompatibleApi() {
        var module = Toml2JsonModule.load();
        var instance = Instance.builder(module).withMachineFactory(Toml2JsonModule::create).build();
        var result = convert(instance, SAMPLE_TOML);
        assertNotNull(result);
        assertTrue(result.contains("\"package\""), "Expected JSON key 'package', got: " + result);
    }

    private String convert(Instance instance, String toml) {
        var memory = instance.memory();
        var allocate = instance.export("allocate");
        var toml2json = instance.export("toml2json");

        byte[] tomlBytes = toml.getBytes(StandardCharsets.UTF_8);
        int inLen = tomlBytes.length;
        long[] inPtrResult = allocate.apply(inLen, 0);
        int inPtr = (int) inPtrResult[0];
        memory.write(inPtr, tomlBytes);

        long[] outPtrResult = allocate.apply(4, 0);
        int outPtr = (int) outPtrResult[0];
        long[] outLenResult = allocate.apply(4, 0);
        int outLen = (int) outLenResult[0];

        toml2json.apply(inPtr, inLen, outPtr, outLen);

        int resultPtr = memory.readInt(outPtr);
        int resultLen = memory.readInt(outLen);
        byte[] resultBytes = memory.readBytes(resultPtr, resultLen);
        return new String(resultBytes, StandardCharsets.UTF_8);
    }
}
