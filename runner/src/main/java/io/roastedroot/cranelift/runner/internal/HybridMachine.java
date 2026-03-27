package io.roastedroot.cranelift.runner.internal;

import com.dylibso.chicory.runtime.Machine;
import com.dylibso.chicory.wasm.ChicoryException;

/**
 * Dispatches function calls between a native (Cranelift) machine and a
 * bytecode (Chicory compiler) machine based on a per-function boolean array.
 *
 * <p>Both machines compile ALL functions. The dispatch only happens at the
 * entry point — once inside a machine, internal calls stay within that machine.
 */
public final class HybridMachine implements Machine {

    private final NativeMachine nativeMachine;
    private final Machine bytecodeMachine;
    private final boolean[] isNative;
    private final int numImports;

    public HybridMachine(
            NativeMachine nativeMachine,
            Machine bytecodeMachine,
            boolean[] isNative,
            int numImports) {
        this.nativeMachine = nativeMachine;
        this.bytecodeMachine = bytecodeMachine;
        this.isNative = isNative;
        this.numImports = numImports;
    }

    /** Package-private: used by NativeTable to resolve funcId → funcPtr. */
    NativeMachine nativeMachine() {
        return nativeMachine;
    }

    @Override
    public long[] call(int funcId, long[] args) throws ChicoryException {
        if (funcId < numImports) {
            return nativeMachine.call(funcId, args);
        }
        if (isNative[funcId]) {
            return nativeMachine.call(funcId, args);
        }
        return bytecodeMachine.call(funcId, args);
    }
}
