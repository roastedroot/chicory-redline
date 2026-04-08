package io.roastedroot.cranelift.api.internal;

import com.dylibso.chicory.wasm.WasmModule;
import com.dylibso.chicory.wasm.types.FunctionType;
import java.util.HashMap;

/**
 * Utility for building canonical type maps from Wasm modules.
 *
 * <p>Structurally equal FunctionTypes get the same canonical index,
 * enabling correct call_indirect type checking with duplicate types.
 */
public final class TypeMapUtils {

    private TypeMapUtils() {}

    /**
     * Build a map from raw type index to canonical type index.
     * Structurally equal FunctionTypes get the same canonical index.
     */
    public static int[] buildCanonicalTypeMap(WasmModule module) {
        var ts = module.typeSection();
        int count = ts.subTypeCount();
        int[] map = new int[count];
        var seen = new HashMap<FunctionType, Integer>();
        for (int i = 0; i < count; i++) {
            var type = ts.getType(i);
            if (type instanceof FunctionType) {
                FunctionType ft = (FunctionType) type;
                Integer canonical = seen.get(ft);
                if (canonical != null) {
                    map[i] = canonical;
                } else {
                    seen.put(ft, i);
                    map[i] = i;
                }
            } else {
                map[i] = i; // non-function types keep their own index
            }
        }
        return map;
    }
}
