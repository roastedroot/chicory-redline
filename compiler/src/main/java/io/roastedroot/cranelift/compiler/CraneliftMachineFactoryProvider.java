package io.roastedroot.cranelift.compiler;

import com.dylibso.chicory.runtime.Memory;
import com.dylibso.chicory.wasm.WasmModule;
import com.dylibso.chicory.wasm.types.MemoryLimits;

/**
 * SPI for Cranelift native compilation backends.
 *
 * <p>Two implementations are provided:
 * <ul>
 *   <li>{@code cranelift-runner} — Java 25+, zero-dependency (Panama FFM)</li>
 *   <li>{@code cranelift-runner-jffi} — Java 11+, requires jffi</li>
 * </ul>
 *
 * <p>Implementations are discovered via {@link java.util.ServiceLoader}.
 * Users typically interact with {@link CraneliftInstance#builder(WasmModule)}
 * rather than using this interface directly.
 *
 * @see CraneliftInstance
 */
public interface CraneliftMachineFactoryProvider {

    /**
     * Create a new builder for the given Wasm module.
     */
    CraneliftInstance.Builder builder(WasmModule module);

    /**
     * Create a native memory instance for the given limits.
     */
    Memory createMemory(MemoryLimits limits);

    /**
     * Priority used when multiple providers are on the classpath.
     * Higher values are preferred. Panama = 100, jffi = 50.
     */
    default int priority() {
        return 0;
    }
}
