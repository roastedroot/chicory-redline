package io.roastedroot.cranelift.runner.internal;

import com.dylibso.chicory.runtime.Instance;
import com.dylibso.chicory.runtime.Machine;
import com.dylibso.chicory.wasm.ChicoryException;
import com.dylibso.chicory.wasm.types.FunctionType;
import com.dylibso.chicory.wasm.types.ValType;
import com.dylibso.chicory.wasm.types.Value;
import io.roastedroot.cranelift.compiler.internal.CtxBuffer;
import io.roastedroot.cranelift.compiler.internal.NativeCompiler;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.ref.Cleaner;
import java.util.ArrayList;

/**
 * Machine implementation that compiles Wasm functions to native x86_64
 * via Cranelift and executes them through Panama FFM.
 *
 * <p>Calling convention for all compiled functions:
 * <pre>
 *   param 0: memBase  (i64/ADDRESS) — pointer to linear memory
 *   param 1: ctxPtr   (i64/ADDRESS) — pointer to call context struct
 *   param 2+: Wasm function parameters
 *   return: Wasm return value
 * </pre>
 *
 * <p>See {@link CtxBuffer} for the full layout definition.
 */
public final class NativeMachine implements Machine {

    private static final int CTX_SIZE = CtxBuffer.CTX_SIZE;
    private static final Cleaner CLEANER = Cleaner.create();

    // Conversion handles for adapting downcalls to uniform (MS, MS, long[]) → long.
    // These use Wasm bit-reinterpretation (not numeric casts).
    private static final MethodHandle LONG_TO_FLOAT;
    private static final MethodHandle LONG_TO_DOUBLE;
    private static final MethodHandle FLOAT_TO_LONG;
    private static final MethodHandle DOUBLE_TO_LONG;

    static {
        try {
            var lookup = MethodHandles.lookup();
            LONG_TO_FLOAT =
                    lookup.findStatic(
                            Value.class,
                            "longToFloat",
                            MethodType.methodType(float.class, long.class));
            LONG_TO_DOUBLE =
                    lookup.findStatic(
                            Value.class,
                            "longToDouble",
                            MethodType.methodType(double.class, long.class));
            FLOAT_TO_LONG =
                    lookup.findStatic(
                            Value.class,
                            "floatToLong",
                            MethodType.methodType(long.class, float.class));
            DOUBLE_TO_LONG =
                    lookup.findStatic(
                            Value.class,
                            "doubleToLong",
                            MethodType.methodType(long.class, double.class));
        } catch (NoSuchMethodException | IllegalAccessException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private final Arena arena;
    private final Instance instance;
    private final MethodHandle[] downcalls;
    private final MemorySegment codeRegion;
    private final long codeRegionSize;
    private final MemorySegment ctxBuffer;
    private final MemorySegment funcTable;
    private final MemorySegment argsBuffer;
    private final MemorySegment globalsBuffer;
    private final MemorySegment funcTypesArray;
    private MemorySegment tablePtrsArray;
    private NativeTable[] nativeTables;
    private boolean tablesInitialized;
    private final int numImports;
    private final int globalCount;
    private boolean importGlobalsInitialized;
    private final Cleaner.Cleanable cleanable;
    // Pending exception from upcall stubs (cannot throw through native frames)
    private volatile Throwable pendingException;

    public NativeMachine(
            Instance instance,
            Arena arena,
            java.util.List<NativeTable> sharedTables,
            MemorySegment sharedGlobalsBuffer,
            byte[][] precompiledCode) {
        this.instance = instance;
        this.arena = arena;
        var module = instance.module();
        this.numImports =
                (int)
                        module.importSection().stream()
                                .filter(
                                        i ->
                                                i.importType()
                                                        == com.dylibso.chicory.wasm.types
                                                                .ExternalType.FUNCTION)
                                .count();
        int totalFuncs = numImports + module.codeSection().functionBodyCount();
        this.downcalls = new MethodHandle[totalFuncs];

        // Allocate call context buffer
        ctxBuffer = arena.allocate(CTX_SIZE, 8);

        // Allocate function pointer table (one i64 per function)
        funcTable = arena.allocate((long) totalFuncs * 8, 8);

        // Globals buffer from factory (already has module globals written by Instance)
        this.globalsBuffer = sharedGlobalsBuffer;
        this.globalCount =
                (int)
                                module.importSection().stream()
                                        .filter(
                                                i ->
                                                        i.importType()
                                                                == com.dylibso.chicory.wasm.types
                                                                        .ExternalType.GLOBAL)
                                        .count()
                        + (module.globalSection() != null
                                ? module.globalSection().globalCount()
                                : 0);

        // Allocate args buffer (separate from ctxBuffer, no fixed arg limit)
        this.argsBuffer = arena.allocate((long) CtxBuffer.ARGS_BUFFER_CAPACITY * 8, 8);

        // Allocate funcTypes array with canonical type indices.
        // Structurally equal FunctionTypes get the same canonical index,
        // enabling correct call_indirect type checking with duplicate types.
        int[] canonicalTypeMap = NativeCompiler.buildCanonicalTypeMap(module);
        this.funcTypesArray = arena.allocate((long) totalFuncs * 4, 4);
        for (int i = 0; i < numImports; i++) {
            int rawType = instance.functionType(i);
            funcTypesArray.set(ValueLayout.JAVA_INT, (long) i * 4, canonicalTypeMap[rawType]);
        }
        for (int i = 0; i < module.functionSection().functionCount(); i++) {
            int funcId = numImports + i;
            int rawType = module.functionSection().getFunctionType(i);
            funcTypesArray.set(ValueLayout.JAVA_INT, (long) funcId * 4, canonicalTypeMap[rawType]);
        }

        // NativeTables: tables are created by Instance via tableFactory, but Instance
        // hasn't finished constructing yet (tables created after Machine). We'll
        // populate the tablePtrs lazily on first call() once tables exist.
        this.nativeTables = null;
        this.tablePtrsArray = MemorySegment.NULL;
        this.tablesInitialized = false;

        // Create CALL_INDIRECT trampoline upcall stub (kept for TABLE.INIT/ELEM.DROP)
        MemorySegment trampolineStub = createTrampolineStub();

        // Create memory.grow upcall stub
        MemorySegment memGrowStub = createMemGrowStub();

        // Write pointers to ctxBuffer
        ctxBuffer.set(ValueLayout.JAVA_LONG, CtxBuffer.FUNC_TABLE_PTR, funcTable.address());
        ctxBuffer.set(ValueLayout.JAVA_LONG, CtxBuffer.TRAMPOLINE_PTR, trampolineStub.address());
        ctxBuffer.set(ValueLayout.JAVA_LONG, CtxBuffer.ARGS_PTR, argsBuffer.address());
        ctxBuffer.set(ValueLayout.JAVA_LONG, CtxBuffer.GLOBALS_PTR, globalsBuffer.address());
        ctxBuffer.set(ValueLayout.JAVA_LONG, CtxBuffer.MEM_GROW_PTR, memGrowStub.address());
        ctxBuffer.set(ValueLayout.JAVA_LONG, CtxBuffer.TABLE_PTRS, tablePtrsArray.address());
        ctxBuffer.set(ValueLayout.JAVA_LONG, CtxBuffer.FUNC_TYPES_PTR, funcTypesArray.address());

        // Compile all module-defined functions (or use pre-compiled code)
        byte[][] compiledCode;
        if (precompiledCode != null) {
            compiledCode = precompiledCode;
        } else {
            var compiler =
                    new NativeCompiler(
                            io.roastedroot.cranelift.compiler.CraneliftTarget.detectHost(), module);
            compiledCode = compiler.compileAll();
        }

        // mmap all compiled code into a single executable region
        long totalSize = 0;
        for (byte[] code : compiledCode) {
            if (code != null) {
                totalSize += align(code.length, 16);
            }
        }
        totalSize = Math.max(totalSize, 4096);
        totalSize = align(totalSize, 4096);
        this.codeRegionSize = totalSize;

        try {
            codeRegion = PanamaExecutor.mmapCode(totalSize);
            long offset = 0;
            for (int i = 0; i < compiledCode.length; i++) {
                if (compiledCode[i] != null) {
                    int funcId = numImports + i;
                    MemorySegment.copy(
                            MemorySegment.ofArray(compiledCode[i]),
                            0,
                            codeRegion,
                            offset,
                            compiledCode[i].length);

                    var funcType =
                            (FunctionType)
                                    module.typeSection()
                                            .getType(module.functionSection().getFunctionType(i));

                    MemorySegment codePtr = codeRegion.asSlice(offset);
                    downcalls[funcId] = createDowncall(codePtr, funcType);

                    // Store native code address in function pointer table
                    funcTable.set(ValueLayout.JAVA_LONG, (long) funcId * 8, codePtr.address());

                    offset += align(compiledCode[i].length, 16);
                }
            }
            PanamaExecutor.mprotectExec(codeRegion, totalSize);

            // Create import upcall stubs and store in function pointer table
            for (int funcId = 0; funcId < numImports; funcId++) {
                var importFunc = instance.imports().function(funcId);
                var funcType = importFunc.functionType();
                MemorySegment stub = createImportStub(funcId, funcType);
                funcTable.set(ValueLayout.JAVA_LONG, (long) funcId * 8, stub.address());
                downcalls[funcId] = null; // imports dispatch through call() directly
            }

        } catch (Throwable e) {
            throw new ChicoryException("Failed to set up native code", e);
        }

        // Capture NativeMemory for cleanup (munmap the reserved 4GB region)
        NativeMemory nativeMemory = instance.memory() instanceof NativeMemory nm ? nm : null;

        // Register cleanup: close arena (frees all off-heap allocations + upcall stubs),
        // munmap the executable code region, and munmap the NativeMemory reservation.
        // Runs on explicit close() or when GC'd.
        this.cleanable =
                CLEANER.register(
                        this, new CleanupAction(arena, codeRegion, codeRegionSize, nativeMemory));
    }

    /** Explicitly release all native resources (arena + code region). Idempotent. */
    public void close() {
        cleanable.clean();
    }

    private record CleanupAction(
            Arena arena, MemorySegment codeRegion, long codeRegionSize, NativeMemory nativeMemory)
            implements Runnable {
        @Override
        public void run() {
            if (nativeMemory != null) {
                nativeMemory.close();
            }
            try {
                arena.close();
            } catch (IllegalStateException e) {
                // ignore — may already be closed
            }
            try {
                PanamaExecutor.munmap(codeRegion, codeRegionSize);
            } catch (Throwable e) {
                // ignore cleanup errors
            }
        }
    }

    private static long align(long value, long alignment) {
        return (value + alignment - 1) & ~(alignment - 1);
    }

    // --- Downcall creation (Java → native) ---

    private MethodHandle createDowncall(MemorySegment codePtr, FunctionType funcType) {
        var layouts = new ArrayList<ValueLayout>();
        layouts.add(ValueLayout.ADDRESS); // memBase
        layouts.add(ValueLayout.ADDRESS); // ctxPtr

        for (ValType param : funcType.params()) {
            layouts.add(valTypeToLayout(param));
        }

        ValueLayout returnLayout = null;
        if (!funcType.returns().isEmpty()) {
            if (funcType.returns().size() > 1) {
                // Multi-return: native function returns single i64 dummy
                returnLayout = ValueLayout.JAVA_LONG;
            } else {
                returnLayout = valTypeToLayout(funcType.returns().get(0));
            }
        }

        FunctionDescriptor desc;
        if (returnLayout != null) {
            desc = FunctionDescriptor.of(returnLayout, layouts.toArray(new ValueLayout[0]));
        } else {
            desc = FunctionDescriptor.ofVoid(layouts.toArray(new ValueLayout[0]));
        }

        MethodHandle handle = Linker.nativeLinker().downcallHandle(codePtr, desc);
        return adaptHandle(handle, funcType);
    }

    /**
     * Adapt a native downcall handle to a uniform type:
     * {@code (MemorySegment memBase, MemorySegment ctxPtr, long[] wasmArgs) -> long}.
     *
     * <p>This eliminates Object[] boxing and enables invokeExact at the call site.
     * Float/double conversions use Wasm bit-reinterpretation (not numeric casts).
     */
    private static MethodHandle adaptHandle(MethodHandle handle, FunctionType funcType) {
        int paramCount = funcType.params().size();

        // Step 1: Adapt return type to long
        if (handle.type().returnType() == void.class) {
            // Void → long: call void handle for side effects, return 0L
            MethodHandle zero =
                    MethodHandles.dropArguments(
                            MethodHandles.constant(long.class, 0L),
                            0,
                            handle.type().parameterList());
            handle = MethodHandles.foldArguments(zero, handle);
        } else if (!funcType.returns().isEmpty() && funcType.returns().size() == 1) {
            ValType retType = funcType.returns().get(0);
            if (retType.equals(ValType.F32)) {
                handle = MethodHandles.filterReturnValue(handle, FLOAT_TO_LONG);
            } else if (retType.equals(ValType.F64)) {
                handle = MethodHandles.filterReturnValue(handle, DOUBLE_TO_LONG);
            }
            // I32 → long widening and I64 identity handled by asType below
        }
        // Multi-return: native returns long dummy, no conversion needed

        // Step 2: Filter float/double params (bit-reinterpret long → float/double)
        for (int i = 0; i < paramCount; i++) {
            ValType paramType = funcType.params().get(i);
            if (paramType.equals(ValType.F32)) {
                handle = MethodHandles.filterArguments(handle, i + 2, LONG_TO_FLOAT);
            } else if (paramType.equals(ValType.F64)) {
                handle = MethodHandles.filterArguments(handle, i + 2, LONG_TO_DOUBLE);
            }
        }

        // Step 3: Normalize remaining params to long and return to long.
        // explicitCastArguments handles long→int narrowing (asType only does widening).
        var targetParams = new Class<?>[2 + paramCount];
        targetParams[0] = MemorySegment.class;
        targetParams[1] = MemorySegment.class;
        for (int i = 0; i < paramCount; i++) {
            targetParams[i + 2] = long.class;
        }
        handle =
                MethodHandles.explicitCastArguments(
                        handle, MethodType.methodType(long.class, targetParams));

        // Step 4: Spread wasm params from long[]
        handle = handle.asSpreader(long[].class, paramCount);
        // Final type: (MemorySegment, MemorySegment, long[]) → long

        return handle;
    }

    // --- Import upcall stubs (native → Java for host imports) ---

    private MemorySegment createImportStub(int funcId, FunctionType funcType) {
        try {
            // Build the native descriptor matching compiled function convention:
            // (ADDRESS memBase, ADDRESS ctxPtr, typed_params...) -> typed_return
            var layouts = new ArrayList<ValueLayout>();
            layouts.add(ValueLayout.ADDRESS); // memBase
            layouts.add(ValueLayout.ADDRESS); // ctxPtr
            for (ValType param : funcType.params()) {
                layouts.add(valTypeToLayout(param));
            }

            ValueLayout returnLayout = null;
            if (!funcType.returns().isEmpty()) {
                returnLayout = valTypeToLayout(funcType.returns().get(0));
            }

            FunctionDescriptor desc;
            if (returnLayout != null) {
                desc = FunctionDescriptor.of(returnLayout, layouts.toArray(new ValueLayout[0]));
            } else {
                desc = FunctionDescriptor.ofVoid(layouts.toArray(new ValueLayout[0]));
            }

            // Build Java param types for the MethodHandle
            var targetParamTypes = new ArrayList<Class<?>>();
            targetParamTypes.add(MemorySegment.class); // memBase
            targetParamTypes.add(MemorySegment.class); // ctxPtr
            for (ValType param : funcType.params()) {
                targetParamTypes.add(valTypeToJavaClass(param));
            }

            // importDispatchDirect reads args from ctxBuffer (written by CALL handler)
            MethodHandle directHandler =
                    MethodHandles.lookup()
                            .bind(
                                    this,
                                    "importDispatchDirect",
                                    MethodType.methodType(long.class, int.class));
            directHandler = MethodHandles.insertArguments(directHandler, 0, funcId);
            // Now: () -> long

            // Drop all native params (the stub ignores them, reads from ctxBuffer)
            MethodHandle dropper =
                    MethodHandles.dropArguments(
                            directHandler, 0, targetParamTypes.toArray(new Class[0]));

            // Cast return type to match native descriptor
            if (returnLayout == null) {
                // Void function: discard the long return value
                var voidType =
                        MethodType.methodType(void.class, targetParamTypes.toArray(new Class[0]));
                dropper = dropper.asType(voidType);
            } else if (!funcType.returns().isEmpty()) {
                var retClass = valTypeToJavaClass(funcType.returns().get(0));
                if (!retClass.equals(long.class)) {
                    dropper =
                            MethodHandles.explicitCastArguments(
                                    dropper,
                                    MethodType.methodType(
                                            retClass, targetParamTypes.toArray(new Class[0])));
                }
            }

            return Linker.nativeLinker().upcallStub(dropper, desc, arena);
        } catch (NoSuchMethodException | IllegalAccessException e) {
            throw new ChicoryException("Failed to create import stub for func " + funcId, e);
        }
    }

    /**
     * Dispatches a function call from native code. Reads args from ctxBuffer.
     * Called by import upcall stubs and bridge stubs for non-native functions.
     */
    @SuppressWarnings("unused")
    private long importDispatchDirect(int funcId) {
        try {
            int argCount = ctxBuffer.get(ValueLayout.JAVA_INT, CtxBuffer.ARG_COUNT);
            long[] args = new long[argCount];
            for (int i = 0; i < argCount; i++) {
                args[i] = argsBuffer.get(ValueLayout.JAVA_LONG, CtxBuffer.argOffset(i));
            }
            if (funcId < numImports) {
                var importFunc = instance.imports().function(funcId);
                long[] result = importFunc.handle().apply(instance, args);
                return (result != null && result.length > 0) ? result[0] : 0L;
            }
            throw new ChicoryException("Function " + funcId + " not compiled");
        } catch (Throwable t) {
            pendingException = t;
            return 0L;
        }
    }

    // --- CALL_INDIRECT trampoline ---

    private MemorySegment createTrampolineStub() {
        try {
            MethodHandle handler =
                    MethodHandles.lookup()
                            .bind(
                                    this,
                                    "callIndirectTrampoline",
                                    MethodType.methodType(long.class, long.class));
            var desc = FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG);
            return Linker.nativeLinker().upcallStub(handler, desc, arena);
        } catch (NoSuchMethodException | IllegalAccessException e) {
            throw new ChicoryException("Failed to create trampoline stub", e);
        }
    }

    @SuppressWarnings("unused")
    private long callIndirectTrampoline(long ctxAddr) {
        try {
            var ctx = MemorySegment.ofAddress(ctxAddr).reinterpret(CTX_SIZE);
            int argCount = ctx.get(ValueLayout.JAVA_INT, CtxBuffer.ARG_COUNT);

            // Negative argCount = table operation sentinel
            if (argCount < 0) {
                return handleTableOperation(argCount);
            }

            // Normal call_indirect path (fallback, rarely used now)
            int typeId = ctx.get(ValueLayout.JAVA_INT, CtxBuffer.TYPE_ID);
            int tableIdx = ctx.get(ValueLayout.JAVA_INT, CtxBuffer.TABLE_IDX);
            int elemIdx = ctx.get(ValueLayout.JAVA_INT, CtxBuffer.ELEM_IDX);

            int funcId = nativeTables[tableIdx].requiredRef(elemIdx);

            // Type check
            int actualTypeIdx = instance.functionType(funcId);
            if (actualTypeIdx != typeId) {
                throw new ChicoryException("indirect call type mismatch");
            }

            long[] args = new long[argCount];
            for (int i = 0; i < argCount; i++) {
                args[i] = argsBuffer.get(ValueLayout.JAVA_LONG, CtxBuffer.argOffset(i));
            }

            long[] result = this.call(funcId, args);
            return result.length > 0 ? result[0] : 0L;
        } catch (Throwable t) {
            pendingException = t;
            return 0L;
        }
    }

    private long handleTableOperation(int opCode) {
        switch (opCode) {
            case -1 -> { // table grow fill
                int oldSize = (int) argsBuffer.get(ValueLayout.JAVA_LONG, CtxBuffer.argOffset(0));
                int newSize = (int) argsBuffer.get(ValueLayout.JAVA_LONG, CtxBuffer.argOffset(1));
                int fillValue = (int) argsBuffer.get(ValueLayout.JAVA_LONG, CtxBuffer.argOffset(2));
                long tableAddr = argsBuffer.get(ValueLayout.JAVA_LONG, CtxBuffer.argOffset(3));
                int tblIdx = (int) argsBuffer.get(ValueLayout.JAVA_LONG, CtxBuffer.argOffset(4));
                var tableBuf =
                        MemorySegment.ofAddress(tableAddr)
                                .reinterpret(
                                        CtxBuffer.TABLE_ENTRIES_OFFSET
                                                + (long) newSize * CtxBuffer.TABLE_ENTRY_SIZE);
                for (int i = oldSize; i < newSize; i++) {
                    writeTableEntry(tableBuf, i, fillValue);
                }
                // Update TableLimits so import validation sees the grown size
                nativeTables[tblIdx].limits().grow(newSize - oldSize);
            }
            case -2 -> { // table fill
                int offset = (int) argsBuffer.get(ValueLayout.JAVA_LONG, CtxBuffer.argOffset(0));
                int end = (int) argsBuffer.get(ValueLayout.JAVA_LONG, CtxBuffer.argOffset(1));
                int fillValue = (int) argsBuffer.get(ValueLayout.JAVA_LONG, CtxBuffer.argOffset(2));
                long tableAddr = argsBuffer.get(ValueLayout.JAVA_LONG, CtxBuffer.argOffset(3));
                var tableBuf =
                        MemorySegment.ofAddress(tableAddr)
                                .reinterpret(
                                        CtxBuffer.TABLE_ENTRIES_OFFSET
                                                + (long) end * CtxBuffer.TABLE_ENTRY_SIZE);
                for (int i = offset; i < end; i++) {
                    writeTableEntry(tableBuf, i, fillValue);
                }
            }
            case -3 -> { // table copy (16-byte entries)
                long srcAddr = argsBuffer.get(ValueLayout.JAVA_LONG, CtxBuffer.argOffset(0));
                long dstAddr = argsBuffer.get(ValueLayout.JAVA_LONG, CtxBuffer.argOffset(1));
                int srcOff = (int) argsBuffer.get(ValueLayout.JAVA_LONG, CtxBuffer.argOffset(2));
                int dstOff = (int) argsBuffer.get(ValueLayout.JAVA_LONG, CtxBuffer.argOffset(3));
                int size = (int) argsBuffer.get(ValueLayout.JAVA_LONG, CtxBuffer.argOffset(4));
                long entrySize = CtxBuffer.TABLE_ENTRY_SIZE;
                var srcBuf =
                        MemorySegment.ofAddress(srcAddr)
                                .reinterpret(
                                        CtxBuffer.TABLE_ENTRIES_OFFSET
                                                + (long) (srcOff + size) * entrySize);
                var dstBuf =
                        MemorySegment.ofAddress(dstAddr)
                                .reinterpret(
                                        CtxBuffer.TABLE_ENTRIES_OFFSET
                                                + (long) (dstOff + size) * entrySize);
                // Copy 16-byte entries with correct overlap handling
                if (dstOff <= srcOff) {
                    for (int i = 0; i < size; i++) {
                        copyTableEntry(srcBuf, srcOff + i, dstBuf, dstOff + i);
                    }
                } else {
                    for (int i = size - 1; i >= 0; i--) {
                        copyTableEntry(srcBuf, srcOff + i, dstBuf, dstOff + i);
                    }
                }
            }
            case -4 -> { // table init
                int tableIdx = (int) argsBuffer.get(ValueLayout.JAVA_LONG, CtxBuffer.argOffset(0));
                int elemIdx = (int) argsBuffer.get(ValueLayout.JAVA_LONG, CtxBuffer.argOffset(1));
                int dstOffset = (int) argsBuffer.get(ValueLayout.JAVA_LONG, CtxBuffer.argOffset(2));
                int srcOffset = (int) argsBuffer.get(ValueLayout.JAVA_LONG, CtxBuffer.argOffset(3));
                int size = (int) argsBuffer.get(ValueLayout.JAVA_LONG, CtxBuffer.argOffset(4));
                // Instance.table(tableIdx) returns NativeTable (set via tableFactory).
                // NativeTable.setRef resolves funcId → funcPtr+typeIdx via funcResolver.
                com.dylibso.chicory.runtime.OpcodeImpl.TABLE_INIT(
                        instance, tableIdx, elemIdx, size, srcOffset, dstOffset);
            }
            case -5 -> { // elem drop
                int elemIdx = (int) argsBuffer.get(ValueLayout.JAVA_LONG, CtxBuffer.argOffset(0));
                instance.setElement(elemIdx, null);
            }
            case -6 -> { // memory.copy
                int dst = (int) argsBuffer.get(ValueLayout.JAVA_LONG, CtxBuffer.argOffset(0));
                int src = (int) argsBuffer.get(ValueLayout.JAVA_LONG, CtxBuffer.argOffset(1));
                int size = (int) argsBuffer.get(ValueLayout.JAVA_LONG, CtxBuffer.argOffset(2));
                instance.memory().copy(dst, src, size);
            }
            case -7 -> { // memory.fill
                int dst = (int) argsBuffer.get(ValueLayout.JAVA_LONG, CtxBuffer.argOffset(0));
                int val = (int) argsBuffer.get(ValueLayout.JAVA_LONG, CtxBuffer.argOffset(1));
                int size = (int) argsBuffer.get(ValueLayout.JAVA_LONG, CtxBuffer.argOffset(2));
                instance.memory().fill((byte) val, dst, dst + size);
            }
            case -8 -> { // memory.init
                int segmentId = (int) argsBuffer.get(ValueLayout.JAVA_LONG, CtxBuffer.argOffset(0));
                int dst = (int) argsBuffer.get(ValueLayout.JAVA_LONG, CtxBuffer.argOffset(1));
                int src = (int) argsBuffer.get(ValueLayout.JAVA_LONG, CtxBuffer.argOffset(2));
                int size = (int) argsBuffer.get(ValueLayout.JAVA_LONG, CtxBuffer.argOffset(3));
                instance.memory().initPassiveSegment(segmentId, dst, src, size);
            }
            case -9 -> { // data.drop
                int segmentId = (int) argsBuffer.get(ValueLayout.JAVA_LONG, CtxBuffer.argOffset(0));
                instance.memory().drop(segmentId);
            }
            default -> throw new ChicoryException("Unknown table operation: " + opCode);
        }
        return 0L;
    }

    /**
     * Write a 16-byte table entry. If funcId is REF_NULL_VALUE, writes a null entry.
     * For funcref values (funcId within valid range), resolves funcId → funcPtr+typeIdx.
     * For externref values (opaque refs outside funcTable range), stores as-is with funcPtr=0.
     */
    private void writeTableEntry(MemorySegment tableBuf, int index, int funcId) {
        long base = CtxBuffer.TABLE_ENTRIES_OFFSET + (long) index * CtxBuffer.TABLE_ENTRY_SIZE;
        if (funcId == Value.REF_NULL_VALUE) {
            tableBuf.set(ValueLayout.JAVA_INT, base + CtxBuffer.ENTRY_TYPE_IDX_OFFSET, 0);
            tableBuf.set(
                    ValueLayout.JAVA_INT,
                    base + CtxBuffer.ENTRY_FUNC_ID_OFFSET,
                    Value.REF_NULL_VALUE);
            tableBuf.set(ValueLayout.JAVA_LONG, base + CtxBuffer.ENTRY_FUNC_PTR_OFFSET, 0L);
        } else {
            int totalFuncs = (int) (funcTable.byteSize() / 8);
            if (funcId >= 0 && funcId < totalFuncs) {
                // Funcref: resolve funcId → funcPtr+typeIdx
                long funcPtr = funcTable.get(ValueLayout.JAVA_LONG, (long) funcId * 8);
                int typeIdx = funcTypesArray.get(ValueLayout.JAVA_INT, (long) funcId * 4);
                tableBuf.set(ValueLayout.JAVA_INT, base + CtxBuffer.ENTRY_TYPE_IDX_OFFSET, typeIdx);
                tableBuf.set(ValueLayout.JAVA_INT, base + CtxBuffer.ENTRY_FUNC_ID_OFFSET, funcId);
                tableBuf.set(
                        ValueLayout.JAVA_LONG, base + CtxBuffer.ENTRY_FUNC_PTR_OFFSET, funcPtr);
            } else {
                // Externref: store opaque ref value, not callable
                tableBuf.set(ValueLayout.JAVA_INT, base + CtxBuffer.ENTRY_TYPE_IDX_OFFSET, 0);
                tableBuf.set(ValueLayout.JAVA_INT, base + CtxBuffer.ENTRY_FUNC_ID_OFFSET, funcId);
                tableBuf.set(ValueLayout.JAVA_LONG, base + CtxBuffer.ENTRY_FUNC_PTR_OFFSET, 0L);
            }
        }
    }

    /** Copy a single 16-byte table entry from src[srcIdx] to dst[dstIdx]. */
    private static void copyTableEntry(
            MemorySegment src, int srcIdx, MemorySegment dst, int dstIdx) {
        long srcBase = CtxBuffer.TABLE_ENTRIES_OFFSET + (long) srcIdx * CtxBuffer.TABLE_ENTRY_SIZE;
        long dstBase = CtxBuffer.TABLE_ENTRIES_OFFSET + (long) dstIdx * CtxBuffer.TABLE_ENTRY_SIZE;
        int typeIdx = src.get(ValueLayout.JAVA_INT, srcBase + CtxBuffer.ENTRY_TYPE_IDX_OFFSET);
        int funcId = src.get(ValueLayout.JAVA_INT, srcBase + CtxBuffer.ENTRY_FUNC_ID_OFFSET);
        long funcPtr = src.get(ValueLayout.JAVA_LONG, srcBase + CtxBuffer.ENTRY_FUNC_PTR_OFFSET);
        dst.set(ValueLayout.JAVA_INT, dstBase + CtxBuffer.ENTRY_TYPE_IDX_OFFSET, typeIdx);
        dst.set(ValueLayout.JAVA_INT, dstBase + CtxBuffer.ENTRY_FUNC_ID_OFFSET, funcId);
        dst.set(ValueLayout.JAVA_LONG, dstBase + CtxBuffer.ENTRY_FUNC_PTR_OFFSET, funcPtr);
    }

    // --- Memory grow upcall stub ---

    private MemorySegment createMemGrowStub() {
        try {
            MethodHandle handler =
                    MethodHandles.lookup()
                            .bind(
                                    this,
                                    "memoryGrowHandler",
                                    MethodType.methodType(long.class, long.class));
            var desc = FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG);
            return Linker.nativeLinker().upcallStub(handler, desc, arena);
        } catch (NoSuchMethodException | IllegalAccessException e) {
            throw new ChicoryException("Failed to create memory grow stub", e);
        }
    }

    @SuppressWarnings("unused")
    private long memoryGrowHandler(long ctxAddr) {
        try {
            var ctx = MemorySegment.ofAddress(ctxAddr).reinterpret(CTX_SIZE);
            int delta = ctx.get(ValueLayout.JAVA_INT, CtxBuffer.MEM_GROW_DELTA);
            var mem = instance.memory();
            int oldPages = mem.grow(delta);
            // Update memory base and page count in ctxBuffer
            if (oldPages != -1 && mem instanceof NativeMemory nativeMemory) {
                ctx.set(
                        ValueLayout.JAVA_LONG,
                        CtxBuffer.MEM_BASE_ADDR,
                        nativeMemory.nativeAddress().address());
                ctx.set(ValueLayout.JAVA_INT, CtxBuffer.MEMORY_PAGES, mem.pages());
            }
            return oldPages;
        } catch (Throwable t) {
            pendingException = t;
            return -1L;
        }
    }

    // --- Globals initialization ---

    /**
     * Lazily replace module-defined GlobalInstance objects with NativeGlobalInstance
     * backed by the off-heap globalsBuffer. Called once on first native call,
     * after Instance.initialize() has created the original GlobalInstance objects.
     *
     * For imported globals, we copy their current value into the buffer (read-only
     * from native code's perspective — imported mutable globals are rare).
     */
    private void initializeImportGlobals() {
        if (importGlobalsInitialized || globalCount == 0) {
            return;
        }
        importGlobalsInitialized = true;

        // Module-defined globals are already NativeGlobalInstance (created by globalFactory).
        // Only need to copy imported global values into the shared buffer.
        int importGlobalCount =
                (int)
                        instance.module().importSection().stream()
                                .filter(
                                        i ->
                                                i.importType()
                                                        == com.dylibso.chicory.wasm.types
                                                                .ExternalType.GLOBAL)
                                .count();

        for (int i = 0; i < importGlobalCount; i++) {
            globalsBuffer.set(ValueLayout.JAVA_LONG, (long) i * 8, instance.global(i).getValue());
        }
    }

    private void initializeNativeTables() {
        if (tablesInitialized) {
            return;
        }
        tablesInitialized = true;

        var module = instance.module();
        int importedTableCount = instance.imports().tableCount();
        int definedTableCount = module.tableSection().tableCount();
        int tableCount = importedTableCount + definedTableCount;

        if (tableCount == 0) {
            this.nativeTables = new NativeTable[0];
            return;
        }

        // Instance.table(i) returns NativeTable (created by tableFactory).
        // Just collect references and build the pointer array.
        this.nativeTables = new NativeTable[tableCount];
        this.tablePtrsArray = arena.allocate((long) tableCount * 8, 8);

        for (int i = 0; i < tableCount; i++) {
            var table = instance.table(i);
            if (table instanceof NativeTable nt) {
                nativeTables[i] = nt;
            } else {
                // Imported table not created by our factory — wrap it
                var tableDef =
                        new com.dylibso.chicory.wasm.types.Table(
                                table.elementType(), table.limits());
                var nt = new NativeTable(tableDef, arena);
                for (int j = 0; j < table.size(); j++) {
                    nt.setRef(j, table.ref(j), instance);
                }
                nativeTables[i] = nt;
            }

            tablePtrsArray.set(
                    ValueLayout.JAVA_LONG, (long) i * 8, nativeTables[i].nativeBuffer().address());
        }

        ctxBuffer.set(ValueLayout.JAVA_LONG, CtxBuffer.TABLE_PTRS, tablePtrsArray.address());
    }

    /** Package-private: used by NativeTable to resolve funcId → funcPtr across modules. */
    MemorySegment getFuncTable() {
        return funcTable;
    }

    /** Package-private: used by NativeTable to resolve funcId → canonicalTypeIdx across modules. */
    MemorySegment getFuncTypesArray() {
        return funcTypesArray;
    }

    private static ChicoryException trapException(int trapCode) {
        return switch (trapCode) {
            case CtxBuffer.TRAP_DIV_BY_ZERO -> new ChicoryException("integer divide by zero");
            case CtxBuffer.TRAP_INT_OVERFLOW -> new ChicoryException("integer overflow");
            case CtxBuffer.TRAP_UNREACHABLE -> new ChicoryException("unreachable");
            case CtxBuffer.TRAP_TRUNC_OVERFLOW ->
                    new ChicoryException("invalid conversion to integer");
            case CtxBuffer.TRAP_OOB -> new ChicoryException("out of bounds memory access");
            case CtxBuffer.TRAP_CALL_STACK_EXHAUSTED ->
                    new ChicoryException("call stack exhausted");
            case CtxBuffer.TRAP_TABLE_OOB -> new ChicoryException("out of bounds table access");
            case CtxBuffer.TRAP_UNDEFINED_ELEMENT -> new ChicoryException("undefined element");
            case CtxBuffer.TRAP_UNINITIALIZED_ELEMENT ->
                    new ChicoryException("uninitialized element");
            case CtxBuffer.TRAP_INDIRECT_CALL_TYPE_MISMATCH ->
                    new ChicoryException("indirect call type mismatch");
            default -> new ChicoryException("trap: unknown code " + trapCode);
        };
    }

    // --- Main dispatch ---

    private ValueLayout valTypeToLayout(ValType type) {
        if (type.equals(ValType.I32)) {
            return ValueLayout.JAVA_INT;
        }
        if (type.equals(ValType.I64)) {
            return ValueLayout.JAVA_LONG;
        }
        if (type.equals(ValType.F32)) {
            return ValueLayout.JAVA_FLOAT;
        }
        if (type.equals(ValType.F64)) {
            return ValueLayout.JAVA_DOUBLE;
        }
        // Reference types (funcref, externref) are opaque i64
        int op = type.opcode();
        if (op == ValType.ID.RefNull || op == ValType.ID.Ref) {
            return ValueLayout.JAVA_LONG;
        }
        throw new ChicoryException("Unsupported type for native: " + type);
    }

    private Class<?> valTypeToJavaClass(ValType type) {
        if (type.equals(ValType.I32)) {
            return int.class;
        }
        if (type.equals(ValType.I64)) {
            return long.class;
        }
        if (type.equals(ValType.F32)) {
            return float.class;
        }
        if (type.equals(ValType.F64)) {
            return double.class;
        }
        int op = type.opcode();
        if (op == ValType.ID.RefNull || op == ValType.ID.Ref) {
            return long.class;
        }
        throw new ChicoryException("Unsupported type: " + type);
    }

    @Override
    public long[] call(int funcId, long[] args) throws ChicoryException {
        if (funcId < numImports) {
            // Host import — delegate directly
            var imprt = instance.imports().function(funcId);
            return imprt.handle().apply(instance, args);
        }

        var handle = downcalls[funcId];

        try {
            var funcType = (FunctionType) instance.type(instance.functionType(funcId));

            initializeImportGlobals();
            initializeNativeTables();

            MemorySegment memBase;
            var mem = instance.memory();
            if (mem instanceof NativeMemory nativeMemory) {
                memBase = nativeMemory.nativeAddress();
                ctxBuffer.set(ValueLayout.JAVA_LONG, CtxBuffer.MEM_BASE_ADDR, memBase.address());
                ctxBuffer.set(ValueLayout.JAVA_INT, CtxBuffer.MEMORY_PAGES, mem.pages());
            } else {
                memBase = MemorySegment.NULL;
            }

            long result = (long) handle.invokeExact(memBase, ctxBuffer, args);

            // Check for traps (pre-checks write trap code to ctxBuffer)
            int trapCode = ctxBuffer.get(ValueLayout.JAVA_INT, CtxBuffer.TRAP_CODE);
            if (trapCode != 0) {
                ctxBuffer.set(ValueLayout.JAVA_INT, CtxBuffer.TRAP_CODE, 0);
                throw trapException(trapCode);
            }

            // Check for exceptions from upcall stubs (cannot throw through native)
            if (pendingException != null) {
                var ex = pendingException;
                pendingException = null;
                if (ex instanceof ChicoryException ce) {
                    throw ce;
                }
                if (ex instanceof RuntimeException re) {
                    throw re;
                }
                throw new ChicoryException("Exception in native upcall", ex);
            }

            if (funcType.returns().isEmpty()) {
                return new long[0];
            }

            if (funcType.returns().size() > 1) {
                // Multi-return: read values from argsBuffer
                long[] results = new long[funcType.returns().size()];
                for (int i = 0; i < results.length; i++) {
                    long raw = argsBuffer.get(ValueLayout.JAVA_LONG, CtxBuffer.argOffset(i));
                    results[i] = narrowReturnValue(raw, funcType.returns().get(i));
                }
                return results;
            }

            return new long[] {result};
        } catch (ChicoryException e) {
            throw e;
        } catch (Throwable e) {
            throw new ChicoryException("Native call failed for func " + funcId, e);
        }
    }

    private static long narrowReturnValue(long raw, ValType type) {
        if (type.equals(ValType.I32)) {
            return (int) raw;
        }
        if (type.equals(ValType.F32)) {
            return Value.floatToLong(Float.intBitsToFloat((int) raw));
        }
        if (type.equals(ValType.F64)) {
            return Value.doubleToLong(Double.longBitsToDouble(raw));
        }
        return raw; // I64
    }
}
