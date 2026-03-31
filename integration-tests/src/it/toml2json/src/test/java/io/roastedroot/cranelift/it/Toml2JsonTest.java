package io.roastedroot.cranelift.it;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dylibso.chicory.runtime.Instance;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class Toml2JsonTest {

    private static final String SAMPLE_TOML = "[package]\nname = \"test\"\nversion = \"1.0.0\"\n";

    @Test
    void testFastMode() {
        try (var ni = Toml2JsonModule.fast().build()) {
            var result = convert(ni.instance(), SAMPLE_TOML);
            assertNotNull(result);
            assertTrue(
                    result.contains("\"package\""), "Expected JSON key 'package', got: " + result);
            assertTrue(result.contains("\"test\""), "Expected JSON value 'test', got: " + result);
        }
    }

    @Test
    void testBuilderAutoDetect() {
        try (var ni = Toml2JsonModule.builder().build()) {
            var result = convert(ni.instance(), SAMPLE_TOML);
            assertNotNull(result);
            assertTrue(
                    result.contains("\"package\""), "Expected JSON key 'package', got: " + result);
        }
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
