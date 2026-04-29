package io.roastedroot.redline.api;

import com.dylibso.chicory.runtime.ImportValues;
import com.dylibso.chicory.runtime.Instance;
import com.dylibso.chicory.runtime.Memory;
import com.dylibso.chicory.wasm.WasmModule;
import com.dylibso.chicory.wasm.types.MemoryLimits;
import java.util.ServiceLoader;

/**
 * AutoCloseable wrapper around a Chicory {@link Instance} backed by native
 * (Redline-compiled) machine code.
 *
 * <p>The static {@link #builder(WasmModule)} method uses {@link ServiceLoader}
 * to discover a {@link RedlineMachineFactoryProvider} on the classpath,
 * so users can simply drop in either {@code redline-runner} (Java 25+) or
 * {@code redline-runner-jffi} (Java 11+) and use the same API:
 *
 * <pre>
 * try (var ni = RedlineInstance.builder(module)
 *         .withPrecompiledCode(code)
 *         .build()) {
 *     ni.instance().export("func").apply();
 * }
 * </pre>
 */
public final class RedlineInstance implements AutoCloseable {

    private final Instance instance;
    private final AutoCloseable factory;
    private final boolean nativeBackend;
    private final Runnable interruptRequester;
    private final Runnable interruptClearer;

    /**
     * Internal: creates a native-backed RedlineInstance.
     * Users should use {@link #builder(WasmModule)} or the generated module's
     * {@code builder()} method instead.
     */
    public RedlineInstance(Instance instance, AutoCloseable factory) {
        this(instance, factory, true, null, null);
    }

    public RedlineInstance(
            Instance instance,
            AutoCloseable factory,
            Runnable interruptRequester,
            Runnable interruptClearer) {
        this(instance, factory, true, interruptRequester, interruptClearer);
    }

    private RedlineInstance(
            Instance instance,
            AutoCloseable factory,
            boolean nativeBackend,
            Runnable interruptRequester,
            Runnable interruptClearer) {
        this.instance = instance;
        this.factory = factory;
        this.nativeBackend = nativeBackend;
        this.interruptRequester = interruptRequester;
        this.interruptClearer = interruptClearer;
    }

    /**
     * Wraps a Chicory bytecode-backed instance as a RedlineInstance.
     * Used by {@code UniversalInstance} when falling back to JVM bytecode.
     */
    public static RedlineInstance forBytecode(Instance instance) {
        return new RedlineInstance(instance, () -> {}, false, null, null);
    }

    /** Return the underlying Chicory {@link Instance}. */
    public Instance instance() {
        return instance;
    }

    /** Convenience shortcut for {@code instance().memory()}. */
    public Memory memory() {
        return instance.memory();
    }

    /**
     * Returns {@code true} if this instance is backed by Redline native code,
     * {@code false} if it fell back to Chicory JVM bytecode.
     */
    public boolean isNative() {
        return nativeBackend;
    }

    public void requestInterrupt() {
        if (interruptRequester != null) {
            interruptRequester.run();
        }
    }

    public void clearInterrupt() {
        if (interruptClearer != null) {
            interruptClearer.run();
        }
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

    private static RedlineMachineFactoryProvider loadProvider() {
        RedlineMachineFactoryProvider best = null;
        for (RedlineMachineFactoryProvider provider :
                ServiceLoader.load(RedlineMachineFactoryProvider.class)) {
            if (best == null || provider.priority() > best.priority()) {
                best = provider;
            }
        }
        if (best == null) {
            throw new IllegalStateException(
                    "No RedlineMachineFactoryProvider found on the classpath. "
                            + "Add either redline-runner (Java 25+) or "
                            + "redline-runner-jffi (Java 11+) as a dependency.");
        }
        return best;
    }

    /**
     * Common builder interface implemented by both the Panama and jffi backends.
     */
    public interface Builder {

        /** Set the precompiled native code (one byte array per Wasm function). */
        Builder withPrecompiledCode(byte[][] precompiledCode);

        /** Set host-provided imports (functions, globals, memories, tables). */
        Builder withImportValues(ImportValues importValues);

        /** Override the default memory limits declared in the Wasm module. */
        Builder withMemoryLimits(MemoryLimits limits);

        /**
         * Whether to automatically call the Wasm {@code _start} export after
         * instantiation. Defaults to {@code true}.
         */
        Builder withStart(boolean start);

        /**
         * Whether to run Wasm module initialization (data/element segments,
         * global initializers). Set to {@code false} to inspect the module
         * before initialization. Defaults to {@code true}.
         */
        Builder withInitialize(boolean init);

        /** Build the instance, selecting the best available backend. */
        RedlineInstance build();
    }
}
