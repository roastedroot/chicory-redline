package io.roastedroot.cranelift4j;

import com.dylibso.chicory.runtime.ImportValues;
import com.dylibso.chicory.runtime.Instance;
import com.dylibso.chicory.runtime.Machine;
import com.dylibso.chicory.wasm.WasmModule;
import com.dylibso.chicory.wasm.types.MemoryLimits;
import io.roastedroot.cranelift.api.CraneliftInstance;
import java.util.function.Function;

/**
 * Smart builder that tries Cranelift native execution first,
 * falling back to Chicory JVM bytecode compilation.
 *
 * <p>Runtime selection:
 * <ol>
 *   <li>If native code is available and a Cranelift runner is on the classpath
 *       (Panama on Java 25+, or jffi on Java 11+), uses native execution.</li>
 *   <li>Otherwise, falls back to pre-compiled Chicory JVM bytecode.</li>
 * </ol>
 */
public final class UniversalInstance {

    private static final System.Logger LOGGER = System.getLogger(UniversalInstance.class.getName());

    private UniversalInstance() {}

    /**
     * Create a builder with automatic backend selection.
     *
     * @param module the parsed Wasm module
     */
    public static Builder builder(WasmModule module) {
        return new Builder(module);
    }

    /**
     * Builder that implements {@link CraneliftInstance.Builder} with fallback logic.
     */
    public static final class Builder implements CraneliftInstance.Builder {

        private final WasmModule module;
        private Function<Instance, Machine> chicoryFallback;
        private byte[][] nativeCode;
        private ImportValues importValues;
        private MemoryLimits memoryLimits;
        private boolean start = true;
        private boolean init = true;

        Builder(WasmModule module) {
            this.module = module;
        }

        @Override
        public Builder withPrecompiledCode(byte[][] precompiledCode) {
            this.nativeCode = precompiledCode;
            return this;
        }

        /** Set the Chicory machine factory to use as fallback. */
        public Builder withChicoryFallback(Function<Instance, Machine> chicoryFallback) {
            this.chicoryFallback = chicoryFallback;
            return this;
        }

        @Override
        public Builder withImportValues(ImportValues importValues) {
            this.importValues = importValues;
            return this;
        }

        @Override
        public Builder withMemoryLimits(MemoryLimits limits) {
            this.memoryLimits = limits;
            return this;
        }

        @Override
        public Builder withStart(boolean start) {
            this.start = start;
            return this;
        }

        @Override
        public Builder withInitialize(boolean init) {
            this.init = init;
            return this;
        }

        @Override
        public CraneliftInstance build() {
            // Priority 1: try native (Cranelift + jffi/Panama)
            if (nativeCode != null) {
                try {
                    var b =
                            CraneliftInstance.builder(module)
                                    .withPrecompiledCode(nativeCode)
                                    .withStart(start)
                                    .withInitialize(init);
                    if (importValues != null) {
                        b.withImportValues(importValues);
                    }
                    if (memoryLimits != null) {
                        b.withMemoryLimits(memoryLimits);
                    }
                    var result = b.build();
                    LOGGER.log(System.Logger.Level.INFO, "Using Cranelift native backend");
                    return result;
                } catch (IllegalStateException e) {
                    // No native runner on classpath — fall through to Chicory
                    LOGGER.log(
                            System.Logger.Level.DEBUG,
                            "Native runner not available, falling back to Chicory"
                                    + " bytecode: {0}",
                            e.getMessage());
                }
            }

            // Priority 2: Chicory JVM bytecode fallback
            if (chicoryFallback == null) {
                throw new IllegalStateException(
                        "No native runner available and no Chicory fallback configured. "
                                + "Call withChicoryFallback() or ensure a native runner "
                                + "is on the classpath.");
            }
            LOGGER.log(System.Logger.Level.INFO, "Using Chicory JVM bytecode backend");
            var ib =
                    Instance.builder(module)
                            .withMachineFactory(chicoryFallback)
                            .withStart(start)
                            .withInitialize(init);
            if (importValues != null) {
                ib.withImportValues(importValues);
            }
            if (memoryLimits != null) {
                ib.withMemoryLimits(memoryLimits);
            }
            return CraneliftInstance.forBytecode(ib.build());
        }
    }
}
