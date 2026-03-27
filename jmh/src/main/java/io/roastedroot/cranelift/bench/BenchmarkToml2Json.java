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
 * Benchmarks toml2json (237KB, 279 functions) — Cranelift native vs Chicory bytecode.
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
    public static class NativeState {
        Instance instance;
        ExportFunction toml2json;
        int inPtr;
        int outPtr;
        int outLen;

        @Setup(Level.Trial)
        public void setup() {
            instance = Toml2JsonModule.fast().build();
            var allocate = instance.export("allocate");
            toml2json = instance.export("toml2json");
            inPtr = (int) allocate.apply(TOML_BYTES.length, 0)[0];
            outPtr = (int) allocate.apply(4, 0)[0];
            outLen = (int) allocate.apply(4, 0)[0];
        }
    }

    @State(Scope.Thread)
    public static class ChicoryState {
        Instance instance;
        ExportFunction toml2json;
        int inPtr;
        int outPtr;
        int outLen;

        @Setup(Level.Trial)
        public void setup() {
            instance =
                    Instance.builder(Toml2JsonChicory.load())
                            .withMachineFactory(Toml2JsonChicory::create)
                            .build();
            var allocate = instance.export("allocate");
            toml2json = instance.export("toml2json");
            inPtr = (int) allocate.apply(TOML_BYTES.length, 0)[0];
            outPtr = (int) allocate.apply(4, 0)[0];
            outLen = (int) allocate.apply(4, 0)[0];
        }
    }

    @Benchmark
    @BenchmarkMode(Mode.Throughput)
    public void chicory(ChicoryState s, Blackhole bh) {
        bh.consume(convert(s.instance, s.toml2json, s.inPtr, s.outPtr, s.outLen));
    }

    @Benchmark
    @BenchmarkMode(Mode.Throughput)
    public void native_(NativeState s, Blackhole bh) {
        bh.consume(convert(s.instance, s.toml2json, s.inPtr, s.outPtr, s.outLen));
    }

    private static byte[] convert(
            Instance instance, ExportFunction toml2json, int inPtr, int outPtr, int outLen) {
        var memory = instance.memory();
        memory.write(inPtr, TOML_BYTES);
        toml2json.apply(inPtr, TOML_BYTES.length, outPtr, outLen);
        int resultPtr = memory.readInt(outPtr);
        int resultLen = memory.readInt(outLen);
        return memory.readBytes(resultPtr, resultLen);
    }
}
