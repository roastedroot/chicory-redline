package io.roastedroot.redline.runner.internal;

import com.dylibso.chicory.runtime.Instance;
import com.dylibso.chicory.runtime.Machine;
import com.dylibso.chicory.wasm.ChicoryException;
import com.dylibso.chicory.wasm.types.FunctionType;
import com.dylibso.chicory.wasm.types.ValType;
import com.dylibso.chicory.wasm.types.Value;
import com.kenai.jffi.CallContext;
import com.kenai.jffi.CallingConvention;
import com.kenai.jffi.Closure;
import com.kenai.jffi.ClosureManager;
import com.kenai.jffi.Function;
import com.kenai.jffi.HeapInvocationBuffer;
import com.kenai.jffi.Invoker;
import com.kenai.jffi.Library;
import com.kenai.jffi.MemoryIO;
import com.kenai.jffi.PageManager;
import com.kenai.jffi.Type;
import io.roastedroot.redline.api.internal.CtxBuffer;
import io.roastedroot.redline.api.internal.TypeMapUtils;
import java.lang.ref.Cleaner;
import java.lang.ref.Reference;
import java.util.ArrayList;
import java.util.List;

/**
 * Machine implementation that compiles Wasm functions to native x86_64
 * via Cranelift and executes them through jffi.
 *
 * <p>Calling convention for all compiled functions:
 * <pre>
 *   param 0: memBase  (i64/ADDRESS) — pointer to linear memory
 *   param 1: ctxPtr   (i64/ADDRESS) — pointer to call context struct
 *   param 2+: Wasm function parameters
 *   return: Wasm return value
 * </pre>
 */
public final class JffiNativeMachine implements Machine {

    private static final int CTX_SIZE = CtxBuffer.CTX_SIZE;
    private static final Cleaner CLEANER = Cleaner.create();
    private static final MemoryIO MEM = MemoryIO.getInstance();
    private static final MemoryIO CHECKED_MEM = MemoryIO.getCheckedInstance();
    private static final Invoker INVOKER = Invoker.getInstance();
    private static final PageManager PM = PageManager.getInstance();

    static final long MEMMOVE_ADDR;
    static final long MEMSET_ADDR;

    static {
        long memmove = 0;
        long memset = 0;
        Library defaultLib = Library.getCachedInstance(null, Library.LAZY | Library.GLOBAL);
        if (defaultLib != null) {
            memmove = defaultLib.getSymbolAddress("memmove");
            memset = defaultLib.getSymbolAddress("memset");
        }
        if (memmove == 0 || memset == 0) {
            // Windows: memmove/memset live in msvcrt or ucrtbase, not the default library
            for (String lib : new String[] {"msvcrt", "ucrtbase"}) {
                Library crt = Library.getCachedInstance(lib, Library.LAZY | Library.GLOBAL);
                if (crt != null) {
                    if (memmove == 0) {
                        memmove = crt.getSymbolAddress("memmove");
                    }
                    if (memset == 0) {
                        memset = crt.getSymbolAddress("memset");
                    }
                    if (memmove != 0 && memset != 0) {
                        break;
                    }
                }
            }
        }
        if (memmove == 0 || memset == 0) {
            throw new ExceptionInInitializerError("memmove/memset not found in native library");
        }
        MEMMOVE_ADDR = memmove;
        MEMSET_ADDR = memset;
    }

    private final Instance instance;
    private final CallContext[] callContexts; // jffi CallContext per compiled func
    private final FunctionType[] funcTypes; // wasm FunctionType per func
    private final long codeRegionAddr;
    private final int codeRegionOsPages;
    private final long ctxBufferAddr;
    private final long funcTableAddr;
    private final long funcTableSize; // byte size
    private final long argsBufferAddr;
    private final long globalsBufferAddr;
    private final long funcTypesArrayAddr;
    private final long funcTypesArraySize; // byte size
    private long tablePtrsArrayAddr;
    private JffiNativeTable[] nativeTables;
    private boolean tablesInitialized;
    private final int numImports;
    private final int globalCount;
    private boolean importGlobalsInitialized;
    private long cachedMemBase;
    private boolean memBaseInitialized;
    private final Cleaner.Cleanable cleanable;
    private volatile Throwable pendingException;

    // Mutable holder for lazily-allocated table state, shared with CleanupAction
    private final LazyTableState lazyTableState = new LazyTableState();

    // Keep closure handles alive to prevent GC
    private final Closure.Handle trampolineHandle;
    private final Closure.Handle memGrowHandle;
    private final Closure.Handle[] importHandles;

    public JffiNativeMachine(
            Instance instance,
            List<JffiNativeTable> sharedTables,
            long sharedGlobalsBufferAddr,
            byte[][] precompiledCode,
            java.util.function.Function<com.dylibso.chicory.wasm.WasmModule, byte[][]>
                    compilerFunction) {
        this.instance = instance;
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
        this.callContexts = new CallContext[totalFuncs];
        this.funcTypes = new FunctionType[totalFuncs];
        this.importHandles = new Closure.Handle[numImports];

        // Allocate call context buffer
        ctxBufferAddr = MEM.allocateMemory(CTX_SIZE, true);

        // Allocate function pointer table (one i64 per function)
        this.funcTableSize = (long) totalFuncs * 8;
        funcTableAddr = MEM.allocateMemory(funcTableSize, true);

        // Globals buffer from factory
        this.globalsBufferAddr = sharedGlobalsBufferAddr;
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

        // Allocate args buffer
        this.argsBufferAddr = MEM.allocateMemory((long) CtxBuffer.ARGS_BUFFER_CAPACITY * 8, true);

        // Allocate funcTypes array with canonical type indices
        int[] canonicalTypeMap = TypeMapUtils.buildCanonicalTypeMap(module);
        this.funcTypesArraySize = (long) totalFuncs * 4;
        this.funcTypesArrayAddr = MEM.allocateMemory(funcTypesArraySize, true);
        for (int i = 0; i < numImports; i++) {
            int rawType = instance.functionType(i);
            MEM.putInt(funcTypesArrayAddr + (long) i * 4, canonicalTypeMap[rawType]);
        }
        for (int i = 0; i < module.functionSection().functionCount(); i++) {
            int funcId = numImports + i;
            int rawType = module.functionSection().getFunctionType(i);
            MEM.putInt(funcTypesArrayAddr + (long) funcId * 4, canonicalTypeMap[rawType]);
        }

        // Tables: populated lazily
        this.nativeTables = null;
        this.tablePtrsArrayAddr = 0;
        this.tablesInitialized = false;

        // Create CALL_INDIRECT trampoline closure
        this.trampolineHandle = createTrampolineStub();

        // Create memory.grow closure
        this.memGrowHandle = createMemGrowStub();

        // Write pointers to ctxBuffer
        MEM.putLong(ctxBufferAddr + CtxBuffer.FUNC_TABLE_PTR, funcTableAddr);
        MEM.putLong(ctxBufferAddr + CtxBuffer.TRAMPOLINE_PTR, trampolineHandle.getAddress());
        MEM.putLong(ctxBufferAddr + CtxBuffer.ARGS_PTR, argsBufferAddr);
        MEM.putLong(ctxBufferAddr + CtxBuffer.GLOBALS_PTR, globalsBufferAddr);
        MEM.putLong(ctxBufferAddr + CtxBuffer.MEM_GROW_PTR, memGrowHandle.getAddress());
        MEM.putLong(ctxBufferAddr + CtxBuffer.TABLE_PTRS, 0L);
        MEM.putLong(ctxBufferAddr + CtxBuffer.FUNC_TYPES_PTR, funcTypesArrayAddr);
        MEM.putLong(ctxBufferAddr + CtxBuffer.MEMMOVE_PTR, MEMMOVE_ADDR);
        MEM.putLong(ctxBufferAddr + CtxBuffer.MEMSET_PTR, MEMSET_ADDR);

        // Use pre-compiled code, or compile at runtime
        byte[][] compiledCode;
        if (precompiledCode != null) {
            compiledCode = precompiledCode;
        } else if (compilerFunction != null) {
            compiledCode = compilerFunction.apply(module);
        } else {
            throw new ChicoryException(
                    "No precompiled code provided. Use the redline-compiler-maven-plugin"
                            + " to precompile, or use JffiNativeMachineFactory.builder(module)"
                            + " for runtime compilation.");
        }

        // Calculate total code size
        long totalSize = 0;
        for (byte[] code : compiledCode) {
            if (code != null) {
                totalSize += align(code.length, 16);
            }
        }
        totalSize = Math.max(totalSize, 4096);
        totalSize = align(totalSize, 4096);

        // Allocate executable code region via PageManager
        int osPageSize = (int) PM.pageSize();
        this.codeRegionOsPages = (int) ((totalSize + osPageSize - 1) / osPageSize);
        this.codeRegionAddr =
                PM.allocatePages(codeRegionOsPages, PageManager.PROT_READ | PageManager.PROT_WRITE);
        if (codeRegionAddr == 0 || codeRegionAddr == -1) {
            throw new ChicoryException("Failed to allocate executable code pages");
        }

        try {
            // Copy code and create Function objects
            long offset = 0;
            for (int i = 0; i < compiledCode.length; i++) {
                if (compiledCode[i] != null) {
                    int funcId = numImports + i;
                    MEM.putByteArray(
                            codeRegionAddr + offset, compiledCode[i], 0, compiledCode[i].length);

                    var funcType =
                            (FunctionType)
                                    module.typeSection()
                                            .getType(module.functionSection().getFunctionType(i));

                    long codePtr = codeRegionAddr + offset;
                    callContexts[funcId] = createCallContext(funcType);
                    funcTypes[funcId] = funcType;

                    // Store native code address in function pointer table
                    MEM.putLong(funcTableAddr + (long) funcId * 8, codePtr);

                    offset += align(compiledCode[i].length, 16);
                }
            }

            // Make code executable
            PM.protectPages(
                    codeRegionAddr,
                    codeRegionOsPages,
                    PageManager.PROT_READ | PageManager.PROT_EXEC);

            // Create import closures and store in function pointer table
            for (int funcId = 0; funcId < numImports; funcId++) {
                var importFunc = instance.imports().function(funcId);
                var funcType = importFunc.functionType();
                funcTypes[funcId] = funcType;
                Closure.Handle handle = createImportStub(funcId, funcType);
                importHandles[funcId] = handle;
                MEM.putLong(funcTableAddr + (long) funcId * 8, handle.getAddress());
            }
        } catch (ChicoryException e) {
            throw e;
        } catch (Throwable e) {
            throw new ChicoryException("Failed to set up native code", e);
        }

        // Capture NativeMemory for cleanup
        JffiNativeMemory nativeMemory =
                instance.memory() instanceof JffiNativeMemory
                        ? (JffiNativeMemory) instance.memory()
                        : null;

        this.cleanable =
                CLEANER.register(
                        this,
                        new CleanupAction(
                                ctxBufferAddr,
                                funcTableAddr,
                                argsBufferAddr,
                                funcTypesArrayAddr,
                                codeRegionAddr,
                                codeRegionOsPages,
                                trampolineHandle,
                                memGrowHandle,
                                importHandles,
                                nativeMemory,
                                lazyTableState));
    }

    public void close() {
        cleanable.clean();
    }

    private static final class LazyTableState {
        volatile long tablePtrsArrayAddr;
        volatile JffiNativeTable[] nativeTables;
        volatile boolean[] ownedTableIndices;
    }

    private static final class CleanupAction implements Runnable {
        private final long ctxBufferAddr;
        private final long funcTableAddr;
        private final long argsBufferAddr;
        private final long funcTypesArrayAddr;
        private final long codeRegionAddr;
        private final int codeRegionOsPages;
        private final Closure.Handle trampolineHandle;
        private final Closure.Handle memGrowHandle;
        private final Closure.Handle[] importHandles;
        private final JffiNativeMemory nativeMemory;
        private final LazyTableState lazyTableState;

        CleanupAction(
                long ctxBufferAddr,
                long funcTableAddr,
                long argsBufferAddr,
                long funcTypesArrayAddr,
                long codeRegionAddr,
                int codeRegionOsPages,
                Closure.Handle trampolineHandle,
                Closure.Handle memGrowHandle,
                Closure.Handle[] importHandles,
                JffiNativeMemory nativeMemory,
                LazyTableState lazyTableState) {
            this.ctxBufferAddr = ctxBufferAddr;
            this.funcTableAddr = funcTableAddr;
            this.argsBufferAddr = argsBufferAddr;
            this.funcTypesArrayAddr = funcTypesArrayAddr;
            this.codeRegionAddr = codeRegionAddr;
            this.codeRegionOsPages = codeRegionOsPages;
            this.trampolineHandle = trampolineHandle;
            this.memGrowHandle = memGrowHandle;
            this.importHandles = importHandles;
            this.nativeMemory = nativeMemory;
            this.lazyTableState = lazyTableState;
        }

        @Override
        public void run() {
            if (nativeMemory != null) {
                nativeMemory.close();
            }
            // Free lazily-allocated table pointer array
            if (lazyTableState.tablePtrsArrayAddr != 0) {
                MEM.freeMemory(lazyTableState.tablePtrsArrayAddr);
            }
            // Free table buffers — only those we created (wrapped imports),
            // not factory-created tables which the factory frees
            if (lazyTableState.nativeTables != null) {
                for (int i = 0; i < lazyTableState.nativeTables.length; i++) {
                    JffiNativeTable table = lazyTableState.nativeTables[i];
                    if (table != null
                            && lazyTableState.ownedTableIndices != null
                            && lazyTableState.ownedTableIndices[i]) {
                        table.free();
                    }
                }
            }
            // Free closures
            trampolineHandle.free();
            memGrowHandle.free();
            for (Closure.Handle h : importHandles) {
                if (h != null) {
                    h.free();
                }
            }
            // Free code region
            if (codeRegionOsPages > 0 && codeRegionAddr != 0) {
                PM.freePages(codeRegionAddr, codeRegionOsPages);
            }
            // Free buffers
            MEM.freeMemory(ctxBufferAddr);
            MEM.freeMemory(funcTableAddr);
            MEM.freeMemory(argsBufferAddr);
            MEM.freeMemory(funcTypesArrayAddr);
        }
    }

    @SuppressWarnings("unchecked")
    private static <E extends Throwable> void sneakyThrow(Throwable e) throws E {
        throw (E) e;
    }

    private static long align(long value, long alignment) {
        return (value + alignment - 1) & ~(alignment - 1);
    }

    // --- jffi type mapping ---

    private static Type valTypeToJffiType(ValType type) {
        if (type.equals(ValType.I32)) {
            return Type.SINT32;
        }
        if (type.equals(ValType.I64)) {
            return Type.SINT64;
        }
        if (type.equals(ValType.F32)) {
            return Type.FLOAT;
        }
        if (type.equals(ValType.F64)) {
            return Type.DOUBLE;
        }
        int op = type.opcode();
        if (op == ValType.ID.RefNull || op == ValType.ID.Ref) {
            return Type.SINT64;
        }
        throw new ChicoryException("Unsupported type for native: " + type);
    }

    private static CallContext createCallContext(FunctionType funcType) {
        var paramTypes = new ArrayList<Type>();
        paramTypes.add(Type.POINTER); // memBase
        paramTypes.add(Type.POINTER); // ctxPtr
        for (ValType param : funcType.params()) {
            paramTypes.add(valTypeToJffiType(param));
        }

        Type returnType;
        if (funcType.returns().isEmpty()) {
            returnType = Type.VOID;
        } else if (funcType.returns().size() > 1) {
            returnType = Type.SINT64; // multi-return: native returns dummy
        } else {
            returnType = valTypeToJffiType(funcType.returns().get(0));
        }

        return new CallContext(returnType, paramTypes.toArray(new Type[0]));
    }

    // --- Import upcall stubs ---

    private Closure.Handle createImportStub(int funcId, FunctionType funcType) {
        var paramTypes = new ArrayList<Type>();
        paramTypes.add(Type.POINTER); // memBase
        paramTypes.add(Type.POINTER); // ctxPtr
        for (ValType param : funcType.params()) {
            paramTypes.add(valTypeToJffiType(param));
        }

        Type returnType;
        if (funcType.returns().isEmpty()) {
            returnType = Type.VOID;
        } else {
            returnType = valTypeToJffiType(funcType.returns().get(0));
        }

        final int fId = funcId;
        final FunctionType ft = funcType;
        Closure closure =
                (Closure.Buffer buf) -> {
                    long result = importDispatchDirect(fId);
                    setClosureReturn(buf, result, ft);
                };

        Closure.Handle handle =
                ClosureManager.getInstance()
                        .newClosure(
                                closure,
                                returnType,
                                paramTypes.toArray(new Type[0]),
                                CallingConvention.DEFAULT);
        return handle;
    }

    private static void setClosureReturn(Closure.Buffer buf, long result, FunctionType funcType) {
        if (funcType.returns().isEmpty()) {
            return;
        }
        ValType retType = funcType.returns().get(0);
        if (retType.equals(ValType.I32)) {
            buf.setIntReturn((int) result);
        } else if (retType.equals(ValType.F32)) {
            buf.setFloatReturn(Float.intBitsToFloat((int) result));
        } else if (retType.equals(ValType.F64)) {
            buf.setDoubleReturn(Double.longBitsToDouble(result));
        } else {
            buf.setLongReturn(result);
        }
    }

    private long importDispatchDirect(int funcId) {
        try {
            if (Thread.interrupted()) {
                requestInterrupt();
                Thread.currentThread().interrupt();
            }
            int argCount = MEM.getInt(ctxBufferAddr + CtxBuffer.ARG_COUNT);
            long[] args = new long[argCount];
            for (int i = 0; i < argCount; i++) {
                args[i] = MEM.getLong(argsBufferAddr + CtxBuffer.argOffset(i));
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

    private Closure.Handle createTrampolineStub() {
        Closure closure =
                (Closure.Buffer buf) -> {
                    long ctxAddr = buf.getLong(0);
                    long result = callIndirectTrampoline(ctxAddr);
                    buf.setLongReturn(result);
                };

        return ClosureManager.getInstance()
                .newClosure(
                        closure, Type.SINT64, new Type[] {Type.SINT64}, CallingConvention.DEFAULT);
    }

    private long callIndirectTrampoline(long ctxAddr) {
        try {
            if (Thread.interrupted()) {
                requestInterrupt();
                Thread.currentThread().interrupt();
            }
            int argCount = MEM.getInt(ctxAddr + CtxBuffer.ARG_COUNT);

            // Negative argCount = table operation sentinel
            if (argCount < 0) {
                return handleTableOperation(argCount);
            }

            // Normal call_indirect path
            int typeId = MEM.getInt(ctxAddr + CtxBuffer.TYPE_ID);
            int tableIdx = MEM.getInt(ctxAddr + CtxBuffer.TABLE_IDX);
            int elemIdx = MEM.getInt(ctxAddr + CtxBuffer.ELEM_IDX);

            int funcId = nativeTables[tableIdx].requiredRef(elemIdx);

            int actualTypeIdx = instance.functionType(funcId);
            if (actualTypeIdx != typeId) {
                throw new ChicoryException("indirect call type mismatch");
            }

            long[] args = new long[argCount];
            for (int i = 0; i < argCount; i++) {
                args[i] = MEM.getLong(argsBufferAddr + CtxBuffer.argOffset(i));
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
            case -1:
                { // table grow fill
                    int oldSize = (int) MEM.getLong(argsBufferAddr + CtxBuffer.argOffset(0));
                    int newSize = (int) MEM.getLong(argsBufferAddr + CtxBuffer.argOffset(1));
                    int fillValue = (int) MEM.getLong(argsBufferAddr + CtxBuffer.argOffset(2));
                    long tableAddr = MEM.getLong(argsBufferAddr + CtxBuffer.argOffset(3));
                    int tblIdx = (int) MEM.getLong(argsBufferAddr + CtxBuffer.argOffset(4));
                    boolean externRef = nativeTables[tblIdx].isExternRef();
                    for (int i = oldSize; i < newSize; i++) {
                        writeTableEntry(tableAddr, i, fillValue, externRef);
                    }
                    nativeTables[tblIdx].limits().grow(newSize - oldSize);
                    break;
                }
            case -2:
                { // table fill
                    int offset = (int) MEM.getLong(argsBufferAddr + CtxBuffer.argOffset(0));
                    int end = (int) MEM.getLong(argsBufferAddr + CtxBuffer.argOffset(1));
                    int fillValue = (int) MEM.getLong(argsBufferAddr + CtxBuffer.argOffset(2));
                    long tableAddr = MEM.getLong(argsBufferAddr + CtxBuffer.argOffset(3));
                    int tblIdx = (int) MEM.getLong(argsBufferAddr + CtxBuffer.argOffset(4));
                    boolean externRef = nativeTables[tblIdx].isExternRef();
                    for (int i = offset; i < end; i++) {
                        writeTableEntry(tableAddr, i, fillValue, externRef);
                    }
                    break;
                }
            case -3:
                { // table copy (16-byte entries)
                    long srcAddr = MEM.getLong(argsBufferAddr + CtxBuffer.argOffset(0));
                    long dstAddr = MEM.getLong(argsBufferAddr + CtxBuffer.argOffset(1));
                    int srcOff = (int) MEM.getLong(argsBufferAddr + CtxBuffer.argOffset(2));
                    int dstOff = (int) MEM.getLong(argsBufferAddr + CtxBuffer.argOffset(3));
                    int size = (int) MEM.getLong(argsBufferAddr + CtxBuffer.argOffset(4));
                    if (dstOff <= srcOff) {
                        for (int i = 0; i < size; i++) {
                            copyTableEntry(srcAddr, srcOff + i, dstAddr, dstOff + i);
                        }
                    } else {
                        for (int i = size - 1; i >= 0; i--) {
                            copyTableEntry(srcAddr, srcOff + i, dstAddr, dstOff + i);
                        }
                    }
                    break;
                }
            case -4:
                { // table init
                    int tableIdx = (int) MEM.getLong(argsBufferAddr + CtxBuffer.argOffset(0));
                    int elemIdx = (int) MEM.getLong(argsBufferAddr + CtxBuffer.argOffset(1));
                    int dstOffset = (int) MEM.getLong(argsBufferAddr + CtxBuffer.argOffset(2));
                    int srcOffset = (int) MEM.getLong(argsBufferAddr + CtxBuffer.argOffset(3));
                    int size = (int) MEM.getLong(argsBufferAddr + CtxBuffer.argOffset(4));
                    com.dylibso.chicory.runtime.OpcodeImpl.TABLE_INIT(
                            instance, tableIdx, elemIdx, size, srcOffset, dstOffset);
                    break;
                }
            case -5:
                { // elem drop
                    int elemIdx = (int) MEM.getLong(argsBufferAddr + CtxBuffer.argOffset(0));
                    instance.setElement(elemIdx, null);
                    break;
                }
            case -8:
                { // memory.init
                    int segmentId = (int) MEM.getLong(argsBufferAddr + CtxBuffer.argOffset(0));
                    int dst = (int) MEM.getLong(argsBufferAddr + CtxBuffer.argOffset(1));
                    int src = (int) MEM.getLong(argsBufferAddr + CtxBuffer.argOffset(2));
                    int size = (int) MEM.getLong(argsBufferAddr + CtxBuffer.argOffset(3));
                    instance.memory().initPassiveSegment(segmentId, dst, src, size);
                    break;
                }
            case -9:
                { // data.drop
                    int segmentId = (int) MEM.getLong(argsBufferAddr + CtxBuffer.argOffset(0));
                    instance.memory().drop(segmentId);
                    break;
                }
            case -10:
                { // memory.atomic.wait32
                    int addr = (int) MEM.getLong(argsBufferAddr + CtxBuffer.argOffset(0));
                    int expected = (int) MEM.getLong(argsBufferAddr + CtxBuffer.argOffset(1));
                    int offset = (int) MEM.getLong(argsBufferAddr + CtxBuffer.argOffset(2));
                    long timeout = MEM.getLong(argsBufferAddr + CtxBuffer.argOffset(3));
                    return instance.memory().atomicWait(addr + offset, expected, timeout);
                }
            case -11:
                { // memory.atomic.wait64
                    int addr = (int) MEM.getLong(argsBufferAddr + CtxBuffer.argOffset(0));
                    long expected = MEM.getLong(argsBufferAddr + CtxBuffer.argOffset(1));
                    int offset = (int) MEM.getLong(argsBufferAddr + CtxBuffer.argOffset(2));
                    long timeout = MEM.getLong(argsBufferAddr + CtxBuffer.argOffset(3));
                    return instance.memory().atomicWait(addr + offset, expected, timeout);
                }
            case -12:
                { // memory.atomic.notify
                    int addr = (int) MEM.getLong(argsBufferAddr + CtxBuffer.argOffset(0));
                    int count = (int) MEM.getLong(argsBufferAddr + CtxBuffer.argOffset(1));
                    int offset = (int) MEM.getLong(argsBufferAddr + CtxBuffer.argOffset(2));
                    return instance.memory().atomicNotify(addr + offset, count);
                }
            default:
                throw new ChicoryException("Unknown trampoline operation: " + opCode);
        }
        return 0L;
    }

    private void writeTableEntry(long tableAddr, int index, int funcId, boolean isExternRef) {
        long base =
                tableAddr
                        + CtxBuffer.TABLE_ENTRIES_OFFSET
                        + (long) index * CtxBuffer.TABLE_ENTRY_SIZE;
        if (funcId == Value.REF_NULL_VALUE) {
            MEM.putInt(base + CtxBuffer.ENTRY_TYPE_IDX_OFFSET, 0);
            MEM.putInt(base + CtxBuffer.ENTRY_FUNC_ID_OFFSET, Value.REF_NULL_VALUE);
            MEM.putLong(base + CtxBuffer.ENTRY_FUNC_PTR_OFFSET, 0L);
        } else if (isExternRef) {
            MEM.putInt(base + CtxBuffer.ENTRY_TYPE_IDX_OFFSET, 0);
            MEM.putInt(base + CtxBuffer.ENTRY_FUNC_ID_OFFSET, funcId);
            MEM.putLong(base + CtxBuffer.ENTRY_FUNC_PTR_OFFSET, 0L);
        } else {
            long funcPtr = MEM.getLong(funcTableAddr + (long) funcId * 8);
            int typeIdx = MEM.getInt(funcTypesArrayAddr + (long) funcId * 4);
            MEM.putInt(base + CtxBuffer.ENTRY_TYPE_IDX_OFFSET, typeIdx);
            MEM.putInt(base + CtxBuffer.ENTRY_FUNC_ID_OFFSET, funcId);
            MEM.putLong(base + CtxBuffer.ENTRY_FUNC_PTR_OFFSET, funcPtr);
        }
    }

    private static void copyTableEntry(long srcAddr, int srcIdx, long dstAddr, int dstIdx) {
        long srcBase =
                srcAddr
                        + CtxBuffer.TABLE_ENTRIES_OFFSET
                        + (long) srcIdx * CtxBuffer.TABLE_ENTRY_SIZE;
        long dstBase =
                dstAddr
                        + CtxBuffer.TABLE_ENTRIES_OFFSET
                        + (long) dstIdx * CtxBuffer.TABLE_ENTRY_SIZE;
        int typeIdx = MEM.getInt(srcBase + CtxBuffer.ENTRY_TYPE_IDX_OFFSET);
        int funcId = MEM.getInt(srcBase + CtxBuffer.ENTRY_FUNC_ID_OFFSET);
        long funcPtr = MEM.getLong(srcBase + CtxBuffer.ENTRY_FUNC_PTR_OFFSET);
        MEM.putInt(dstBase + CtxBuffer.ENTRY_TYPE_IDX_OFFSET, typeIdx);
        MEM.putInt(dstBase + CtxBuffer.ENTRY_FUNC_ID_OFFSET, funcId);
        MEM.putLong(dstBase + CtxBuffer.ENTRY_FUNC_PTR_OFFSET, funcPtr);
    }

    // --- Memory grow upcall stub ---

    private Closure.Handle createMemGrowStub() {
        Closure closure =
                (Closure.Buffer buf) -> {
                    long ctxAddr = buf.getLong(0);
                    long result = memoryGrowHandler(ctxAddr);
                    buf.setLongReturn(result);
                };

        return ClosureManager.getInstance()
                .newClosure(
                        closure, Type.SINT64, new Type[] {Type.SINT64}, CallingConvention.DEFAULT);
    }

    private long memoryGrowHandler(long ctxAddr) {
        try {
            if (Thread.interrupted()) {
                requestInterrupt();
                Thread.currentThread().interrupt();
            }
            int delta = MEM.getInt(ctxAddr + CtxBuffer.MEM_GROW_DELTA);
            var mem = instance.memory();
            int oldPages = mem.grow(delta);
            if (oldPages != -1 && mem instanceof JffiNativeMemory) {
                JffiNativeMemory nativeMemory = (JffiNativeMemory) mem;
                MEM.putLong(ctxAddr + CtxBuffer.MEM_BASE_ADDR, nativeMemory.nativeAddress());
                MEM.putInt(ctxAddr + CtxBuffer.MEMORY_PAGES, mem.pages());
            }
            return oldPages;
        } catch (Throwable t) {
            pendingException = t;
            return -1L;
        }
    }

    // --- Globals initialization ---

    private void initializeImportGlobals() {
        if (importGlobalsInitialized || globalCount == 0) {
            return;
        }
        importGlobalsInitialized = true;

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
            MEM.putLong(globalsBufferAddr + (long) i * 8, instance.global(i).getValue());
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
            this.nativeTables = new JffiNativeTable[0];
            lazyTableState.nativeTables = this.nativeTables;
            return;
        }

        this.nativeTables = new JffiNativeTable[tableCount];
        boolean[] owned = new boolean[tableCount];
        this.tablePtrsArrayAddr = MEM.allocateMemory((long) tableCount * 8, true);

        for (int i = 0; i < tableCount; i++) {
            var table = instance.table(i);
            if (table instanceof JffiNativeTable) {
                nativeTables[i] = (JffiNativeTable) table;
            } else {
                // Imported table not created by our factory — wrap it
                var tableDef =
                        new com.dylibso.chicory.wasm.types.Table(
                                table.elementType(), table.limits());
                var nt = new JffiNativeTable(tableDef);
                for (int j = 0; j < table.size(); j++) {
                    nt.setRef(j, table.ref(j), instance);
                }
                nativeTables[i] = nt;
                owned[i] = true;
            }

            MEM.putLong(tablePtrsArrayAddr + (long) i * 8, nativeTables[i].nativeBufferAddress());
        }

        // Update lazy state so CleanupAction can free these
        lazyTableState.tablePtrsArrayAddr = this.tablePtrsArrayAddr;
        lazyTableState.nativeTables = this.nativeTables;
        lazyTableState.ownedTableIndices = owned;

        MEM.putLong(ctxBufferAddr + CtxBuffer.TABLE_PTRS, tablePtrsArrayAddr);
    }

    /** Package-private: used by JffiNativeTable to resolve funcId → funcPtr. */
    long getFuncTableAddress() {
        return funcTableAddr;
    }

    /** Package-private: byte size of func table. */
    long getFuncTableSize() {
        return funcTableSize;
    }

    /** Package-private: used by JffiNativeTable to resolve funcId → canonicalTypeIdx. */
    long getFuncTypesArrayAddress() {
        return funcTypesArrayAddr;
    }

    private static ChicoryException trapException(int trapCode) {
        if (trapCode == CtxBuffer.TRAP_DIV_BY_ZERO) {
            return new ChicoryException("integer divide by zero");
        }
        if (trapCode == CtxBuffer.TRAP_INT_OVERFLOW) {
            return new ChicoryException("integer overflow");
        }
        if (trapCode == CtxBuffer.TRAP_UNREACHABLE) {
            return new ChicoryException("unreachable");
        }
        if (trapCode == CtxBuffer.TRAP_TRUNC_OVERFLOW) {
            return new ChicoryException("integer overflow");
        }
        if (trapCode == CtxBuffer.TRAP_TRUNC_NAN) {
            return new ChicoryException("invalid conversion to integer");
        }
        if (trapCode == CtxBuffer.TRAP_OOB) {
            return new ChicoryException("out of bounds memory access");
        }
        if (trapCode == CtxBuffer.TRAP_CALL_STACK_EXHAUSTED) {
            return new ChicoryException("call stack exhausted");
        }
        if (trapCode == CtxBuffer.TRAP_TABLE_OOB) {
            return new ChicoryException("out of bounds table access");
        }
        if (trapCode == CtxBuffer.TRAP_UNDEFINED_ELEMENT) {
            return new ChicoryException("undefined element");
        }
        if (trapCode == CtxBuffer.TRAP_UNINITIALIZED_ELEMENT) {
            return new ChicoryException("uninitialized element");
        }
        if (trapCode == CtxBuffer.TRAP_INDIRECT_CALL_TYPE_MISMATCH) {
            return new ChicoryException("indirect call type mismatch");
        }
        if (trapCode == CtxBuffer.TRAP_UNALIGNED_ATOMIC) {
            return new ChicoryException("unaligned atomic");
        }
        if (trapCode == CtxBuffer.TRAP_INTERRUPTED) {
            return new ChicoryException("interrupted");
        }
        return new ChicoryException("trap: unknown code " + trapCode);
    }

    // --- Native function invocation ---

    private long invokeNative(
            CallContext callCtx,
            long funcAddr,
            FunctionType funcType,
            long memBase,
            long ctxPtr,
            long[] wasmArgs) {
        int nativeArgCount = 2 + wasmArgs.length;

        // Fast path: ≤6 native args use invokeN* (CallContext + address + args)
        switch (nativeArgCount) {
            case 2:
                return INVOKER.invokeN2(callCtx, funcAddr, memBase, ctxPtr);
            case 3:
                return INVOKER.invokeN3(callCtx, funcAddr, memBase, ctxPtr, wasmArgs[0]);
            case 4:
                return INVOKER.invokeN4(
                        callCtx, funcAddr, memBase, ctxPtr, wasmArgs[0], wasmArgs[1]);
            case 5:
                return INVOKER.invokeN5(
                        callCtx, funcAddr, memBase, ctxPtr, wasmArgs[0], wasmArgs[1], wasmArgs[2]);
            case 6:
                return INVOKER.invokeN6(
                        callCtx,
                        funcAddr,
                        memBase,
                        ctxPtr,
                        wasmArgs[0],
                        wasmArgs[1],
                        wasmArgs[2],
                        wasmArgs[3]);
            default:
                // >6 args: use HeapInvocationBuffer
                return invokeViaBuffer(callCtx, funcAddr, funcType, memBase, ctxPtr, wasmArgs);
        }
    }

    private long invokeViaBuffer(
            CallContext callCtx,
            long funcAddr,
            FunctionType funcType,
            long memBase,
            long ctxPtr,
            long[] wasmArgs) {
        var func = new Function(funcAddr, callCtx);
        var buffer = new HeapInvocationBuffer(func);
        buffer.putAddress(memBase);
        buffer.putAddress(ctxPtr);
        for (int i = 0; i < wasmArgs.length; i++) {
            ValType paramType = funcType.params().get(i);
            if (paramType.equals(ValType.I32)) {
                buffer.putInt((int) wasmArgs[i]);
            } else if (paramType.equals(ValType.F32)) {
                buffer.putFloat(Float.intBitsToFloat((int) wasmArgs[i]));
            } else if (paramType.equals(ValType.F64)) {
                buffer.putDouble(Double.longBitsToDouble(wasmArgs[i]));
            } else {
                // I64, ref types
                buffer.putLong(wasmArgs[i]);
            }
        }

        // invokeLong works for all return types in buffer path
        return INVOKER.invokeLong(func, buffer);
    }

    // --- Main dispatch ---

    @Override
    public long[] call(int funcId, long[] args) throws ChicoryException {
        if (funcId < numImports) {
            // Host import — delegate directly
            var imprt = instance.imports().function(funcId);
            return imprt.handle().apply(instance, args);
        }

        var callCtx = callContexts[funcId];
        var funcType = funcTypes[funcId];
        long funcAddr = MEM.getLong(funcTableAddr + (long) funcId * 8);

        try {
            initializeImportGlobals();
            initializeNativeTables();

            // Reset stack limit so native code re-initializes from calling thread's RSP
            MEM.putLong(ctxBufferAddr + CtxBuffer.STACK_LIMIT, 0L);

            if (!memBaseInitialized) {
                var mem = instance.memory();
                if (mem instanceof JffiNativeMemory) {
                    cachedMemBase = ((JffiNativeMemory) mem).nativeAddress();
                    MEM.putLong(ctxBufferAddr + CtxBuffer.MEM_BASE_ADDR, cachedMemBase);
                } else if (mem != null) {
                    throw new ChicoryException(
                            "JffiNativeMachine requires JffiNativeMemory but got "
                                    + mem.getClass().getName()
                                    + ". Use JffiNativeMachineFactory.createMemory() for all"
                                    + " memories, including imports.");
                } else {
                    cachedMemBase = 0L;
                }
                memBaseInitialized = true;
            }
            // MEMORY_PAGES can change via grow() — must refresh every call
            var mem = instance.memory();
            if (mem != null) {
                MEM.putInt(ctxBufferAddr + CtxBuffer.MEMORY_PAGES, mem.pages());
            }

            if (Thread.interrupted()) {
                throw new ChicoryException("interrupted");
            }

            Thread caller = Thread.currentThread();
            Thread watchdog =
                    new Thread(
                            () -> {
                                while (!Thread.currentThread().isInterrupted()) {
                                    if (caller.isInterrupted()) {
                                        requestInterrupt();
                                        return;
                                    }
                                    try {
                                        Thread.sleep(1);
                                    } catch (InterruptedException e) {
                                        return;
                                    }
                                }
                            });
            watchdog.setDaemon(true);
            watchdog.start();
            long result;
            try {
                result =
                        invokeNative(
                                callCtx, funcAddr, funcType, cachedMemBase, ctxBufferAddr, args);
            } finally {
                watchdog.interrupt();
            }

            // Check for exceptions from upcall stubs first — a host function
            // may throw (e.g. WasiExitException from proc_exit) and then the
            // native code hits unreachable, setting both pendingException and
            // a trap code.  The pending exception is the real cause.
            if (pendingException != null) {
                var ex = pendingException;
                pendingException = null;
                MEM.putInt(ctxBufferAddr + CtxBuffer.TRAP_CODE, 0);
                sneakyThrow(ex);
            }

            // Check for traps
            int trapCode = MEM.getInt(ctxBufferAddr + CtxBuffer.TRAP_CODE);
            if (trapCode != 0) {
                MEM.putInt(ctxBufferAddr + CtxBuffer.TRAP_CODE, 0);
                if (trapCode == CtxBuffer.TRAP_INTERRUPTED) {
                    CHECKED_MEM.putLong(ctxBufferAddr + CtxBuffer.INTERRUPT_FLAG, 0L);
                    Thread.currentThread().interrupt();
                }
                throw trapException(trapCode);
            }

            if (funcType.returns().isEmpty()) {
                return new long[0];
            }

            if (funcType.returns().size() > 1) {
                // Multi-return: read values from argsBuffer
                long[] results = new long[funcType.returns().size()];
                for (int i = 0; i < results.length; i++) {
                    long raw = MEM.getLong(argsBufferAddr + CtxBuffer.argOffset(i));
                    results[i] = narrowReturnValue(raw, funcType.returns().get(i));
                }
                return results;
            }

            return new long[] {result};
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable e) {
            sneakyThrow(e);
            throw new AssertionError("unreachable");
        } finally {
            // Prevent the JIT from considering this machine unreachable during
            // the native call, which would let the Cleaner free native memory
            // (ctxBuffer, funcTypesArray, code region) while code is executing.
            Reference.reachabilityFence(this);
        }
    }

    public void requestInterrupt() {
        CHECKED_MEM.putLong(ctxBufferAddr + CtxBuffer.INTERRUPT_FLAG, 1L);
    }

    public void clearInterrupt() {
        CHECKED_MEM.putLong(ctxBufferAddr + CtxBuffer.INTERRUPT_FLAG, 0L);
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
