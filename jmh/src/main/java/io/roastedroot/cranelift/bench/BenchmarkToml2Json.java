package io.roastedroot.cranelift.bench;

import com.dylibso.chicory.runtime.ExportFunction;
import com.dylibso.chicory.runtime.Instance;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

/**
 * Compares safe (Chicory bytecode) vs fast (hybrid native+bytecode) execution of toml2json — a
 * real-world 237KB Wasm module with 279 functions.
 *
 * <p>Allocates input/output buffers once in setup and reuses them across calls to avoid exhausting
 * the wasm linear memory (the allocator grows pages with no deallocation).
 *
 * <p>Run from the repo root: {@code jmh/run.sh}
 */
@Warmup(iterations = 2)
@Measurement(iterations = 5)
@OutputTimeUnit(TimeUnit.SECONDS)
@Fork(value = 1, jvmArgs = "--enable-native-access=ALL-UNNAMED")
public class BenchmarkToml2Json {

    private static final byte[] TOML_BYTES =
            ("[package]\n"
                            + "name = \"toml2json\"\n"
                            + "version = \"0.1.0\"\n"
                            + "edition = \"2021\"\n"
                            + "\n"
                            + "[dependencies]\n"
                            + "serde = { version = \"1.0\", features = [\"derive\"] }\n"
                            + "serde_json = \"1.0\"\n"
                            + "toml = \"0.8\"\n")
                    .getBytes(StandardCharsets.UTF_8);

    @State(Scope.Thread)
    public static class SafeState {
        Instance instance;
        ExportFunction allocate;
        ExportFunction toml2json;
        int inPtr;
        int outPtr;
        int outLen;

        @Setup(Level.Trial)
        public void setup() {
            instance = Toml2JsonModule.safe().build();
            allocate = instance.export("allocate");
            toml2json = instance.export("toml2json");
            // Pre-allocate buffers once — reused across all calls
            inPtr = (int) allocate.apply(TOML_BYTES.length, 0)[0];
            outPtr = (int) allocate.apply(4, 0)[0];
            outLen = (int) allocate.apply(4, 0)[0];
        }
    }

    @State(Scope.Thread)
    public static class FastState {
        Instance instance;
        ExportFunction allocate;
        ExportFunction toml2json;
        int inPtr;
        int outPtr;
        int outLen;

        @Setup(Level.Trial)
        public void setup() {
            instance = Toml2JsonModule.fast().build();
            allocate = instance.export("allocate");
            toml2json = instance.export("toml2json");
            inPtr = (int) allocate.apply(TOML_BYTES.length, 0)[0];
            outPtr = (int) allocate.apply(4, 0)[0];
            outLen = (int) allocate.apply(4, 0)[0];
        }
    }

    @Benchmark
    @BenchmarkMode(Mode.Throughput)
    public void benchmarkSafe(SafeState s, Blackhole bh) {
        bh.consume(convert(s));
    }

    @Benchmark
    @BenchmarkMode(Mode.Throughput)
    public void benchmarkFast(FastState s, Blackhole bh) {
        bh.consume(convert(s));
    }

    private static byte[] convert(SafeState s) {
        return doConvert(s.instance, s.toml2json, s.inPtr, s.outPtr, s.outLen);
    }

    private static byte[] convert(FastState s) {
        return doConvert(s.instance, s.toml2json, s.inPtr, s.outPtr, s.outLen);
    }

    private static byte[] doConvert(
            Instance instance, ExportFunction toml2json, int inPtr, int outPtr, int outLen) {
        var memory = instance.memory();
        // Write input (same buffer, overwritten each time)
        memory.write(inPtr, TOML_BYTES);
        // Call toml2json (writes result ptr/len to outPtr/outLen)
        toml2json.apply(inPtr, TOML_BYTES.length, outPtr, outLen);
        // Read result
        int resultPtr = memory.readInt(outPtr);
        int resultLen = memory.readInt(outLen);
        return memory.readBytes(resultPtr, resultLen);
    }
}
