package io.roastedroot.redline.bridge;

import com.dylibso.chicory.annotations.WasmModuleInterface;
import com.dylibso.chicory.runtime.ImportValues;
import com.dylibso.chicory.runtime.Instance;
import com.dylibso.chicory.wasi.WasiOptions;
import com.dylibso.chicory.wasi.WasiPreview1;

/**
 * Java wrapper around the redline-bridge.wasm module.
 * Provides typed methods that map to Cranelift's FunctionBuilder API.
 * All IDs (block, var, value) are explicit — Java tracks them.
 */
@WasmModuleInterface(WasmResource.absoluteFile)
public final class RedlineBridge implements AutoCloseable {

    private final Instance instance;
    private final WasiPreview1 wasi;
    private final RedlineBridge_ModuleExports exports;

    public RedlineBridge() {
        var wasiOpts =
                WasiOptions.builder()
                        // DEBUG: .inheritSystem()
                        .build();
        wasi = WasiPreview1.builder().withOptions(wasiOpts).build();
        var imports = ImportValues.builder().addFunction(wasi.toHostFunctions()).build();

        instance =
                Instance.builder(Cranelift.load())
                        .withImportValues(imports)
                        .withMachineFactory(Cranelift::create)
                        .build();
        exports = new RedlineBridge_ModuleExports(instance);
    }

    @Override
    public void close() {
        if (wasi != null) {
            wasi.close();
        }
    }

    public static final int TYPE_I32 = 0;
    public static final int TYPE_I64 = 1;
    public static final int TYPE_F32 = 2;
    public static final int TYPE_F64 = 3;

    public void init(String target) {
        byte[] bytes = target.getBytes();
        int ptr = exports.wasmMalloc(bytes.length);
        for (int i = 0; i < bytes.length; i++) {
            exports.memory().writeByte(ptr + i, bytes[i]);
        }
        exports.init(ptr, bytes.length);
        exports.wasmFree(ptr, bytes.length);
    }

    public RedlineBridge_ModuleExports exports() {
        return exports;
    }

    public byte[] compile() {
        exports.compile();
        int codePtr = exports.getCodePtr();
        int codeLen = exports.getCodeLen();
        return exports.memory().readBytes(codePtr, codeLen);
    }
}
