package io.roastedroot.cranelift.compiler;

import com.dylibso.chicory.runtime.ImportValues;
import com.dylibso.chicory.runtime.Instance;
import com.dylibso.chicory.runtime.Memory;
import com.dylibso.chicory.wasm.WasmModule;
import com.dylibso.chicory.wasm.types.MemoryLimits;
import java.util.Iterator;
import java.util.ServiceLoader;

/**
 * AutoCloseable wrapper around a Chicory {@link Instance} backed by native
 * (Cranelift-compiled) machine code.
 *
 * <p>The static {@link #builder(WasmModule)} method uses {@link ServiceLoader}
 * to discover a {@link CraneliftMachineFactoryProvider} on the classpath,
 * so users can simply drop in either {@code cranelift-runner} (Java 25+) or
 * {@code cranelift-runner-jffi} (Java 11+) and use the same API:
 *
 * <pre>
 * try (var ni = CraneliftInstance.builder(module)
 *         .withPrecompiledCode(code)
 *         .build()) {
 *     ni.instance().export("func").apply();
 * }
 * </pre>
 */
public final class CraneliftInstance implements AutoCloseable {

    private final Instance instance;
    private final AutoCloseable factory;

    public CraneliftInstance(Instance instance, AutoCloseable factory) {
        this.instance = instance;
        this.factory = factory;
    }

    /** Return the underlying Chicory {@link Instance}. */
    public Instance instance() {
        return instance;
    }

    @SuppressWarnings("IllegalCatch")
    @Override
    public void close() {
        try {
            factory.close();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) { // NOPMD - AutoCloseable.close() throws Exception
            throw new RuntimeException(e);
        }
    }

    /**
     * Create a builder that discovers the runtime backend via SPI.
     *
     * @throws IllegalStateException if no provider is found on the classpath
     */
    public static Builder builder(WasmModule module) {
        return loadProvider().builder(module);
    }

    /**
     * Create a native memory using the SPI-discovered backend.
     *
     * @throws IllegalStateException if no provider is found on the classpath
     */
    public static Memory createMemory(MemoryLimits limits) {
        return loadProvider().createMemory(limits);
    }

    private static CraneliftMachineFactoryProvider loadProvider() {
        Iterator<CraneliftMachineFactoryProvider> it =
                ServiceLoader.load(CraneliftMachineFactoryProvider.class).iterator();
        if (!it.hasNext()) {
            throw new IllegalStateException(
                    "No CraneliftMachineFactoryProvider found on the classpath. "
                            + "Add either cranelift-runner (Java 25+) or "
                            + "cranelift-runner-jffi (Java 11+) as a dependency.");
        }
        return it.next();
    }

    /**
     * Common builder interface implemented by both the Panama and jffi backends.
     */
    public interface Builder {

        Builder withPrecompiledCode(byte[][] precompiledCode);

        Builder withImportValues(ImportValues importValues);

        Builder withMemoryLimits(MemoryLimits limits);

        Builder withStart(boolean start);

        Builder withInitialize(boolean init);

        CraneliftInstance build();
    }
}
