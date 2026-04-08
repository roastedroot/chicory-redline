package io.roastedroot.cranelift.api.internal;

/**
 * Defines the layout of the shared context buffer (ctxBuffer) used to pass
 * data between Java and native compiled code.
 *
 * <p>The buffer is a flat byte array allocated off-heap via Panama's Arena.
 * Native code accesses it through the ctxPtr parameter (second arg of every
 * compiled function). Java code accesses it through MemorySegment get/set.
 *
 * <h3>Re-entrancy safety</h3>
 *
 * <p>The ctxBuffer is shared across all call depths. Re-entrancy is safe because:
 * <ul>
 *   <li>All Java-side readers ({@code callIndirectTrampoline}, {@code importDispatchDirect})
 *       copy values from ctxBuffer/argsBuffer into Java local variables <em>before</em>
 *       dispatching the call. The re-entrant call may overwrite the buffers, but the
 *       outer reader has already captured what it needs.</li>
 *   <li>Native-to-native direct calls pass args via CPU registers (System V ABI).
 *       The ctxBuffer/argsBuffer writes before a CALL are only for import stubs;
 *       native callees never read them.</li>
 *   <li>trapCode is written by native pre-check blocks that immediately return.
 *       A trap handler never calls another function, so no re-entrancy conflict.</li>
 *   <li>{@code call()} sets memBaseAddr/memoryPages before each native invocation and
 *       reads trapCode after. An inner {@code call()} resets trapCode for its own check,
 *       which cannot interfere because the outer native frame has not yet returned.</li>
 * </ul>
 *
 * <pre>
 * Offset  Type   Field             Description
 * ──────  ─────  ────────────────  ──────────────────────────────────────────
 *   0     i64    funcTablePtr      Pointer to function pointer table
 *   8     i64    trampolinePtr     Upcall stub for CALL_INDIRECT fallback
 *  16     i32    trapCode          Trap code written by native pre-checks
 *  20     i32    typeId            CALL_INDIRECT: expected type index
 *  24     i32    tableIdx          CALL_INDIRECT: table index
 *  28     i32    elemIdx           CALL_INDIRECT: table element index
 *  32     i32    argCount          Number of call arguments
 *  36     i32    memGrowDelta      Page count delta for memory.grow
 *  40     i64    argsPtr           Pointer to separate args buffer
 *  48     i64    stackLimit        Stack pointer limit for call depth guard
 *  56     i64    memmovePtr        Pointer to libc memmove
 *  64     i64    memsetPtr         Pointer to libc memset
 * 200     i64    globalsPtr        Pointer to globals buffer
 * 208     i64    memGrowPtr        Upcall stub for memory.grow
 * 216     i32    memoryPages       Current memory page count
 * 220     ---    (padding)
 * 224     i64    memBaseAddr       Current memory base address
 * 232     i64    tablePtrs        Pointer to array of table buffer pointers
 * 240     i64    funcTypesPtr     Pointer to funcTypes array (i32 per func)
 * ──────  ─────  ────────────────  ──────────────────────────────────────────
 * Total: 248 bytes used, 256 allocated (CTX_SIZE)
 *
 * Args buffer (separate allocation, pointed to by argsPtr):
 *   [0]    i64   arg0
 *   [8]    i64   arg1
 *   ...
 *   [N*8]  i64   argN
 * Size: ARGS_BUFFER_CAPACITY * 8 bytes
 * </pre>
 */
public final class CtxBuffer {

    private CtxBuffer() {}

    /** Total allocated size of the context buffer. */
    public static final int CTX_SIZE = 256; // fields up to offset 248, padded to 256

    /** Number of i64 slots in the args buffer. */
    public static final int ARGS_BUFFER_CAPACITY = 1024;

    // --- Fixed pointer slots (written once at init) ---

    /** Pointer to the function pointer table (one i64 per function). */
    public static final int FUNC_TABLE_PTR = 0;

    /** Pointer to the CALL_INDIRECT trampoline upcall stub. */
    public static final int TRAMPOLINE_PTR = 8;

    // --- Trap reporting ---

    /** Trap code written by native pre-check blocks. 0 = no trap. */
    public static final int TRAP_CODE = 16;

    // --- CALL_INDIRECT metadata ---

    /** Expected type index for indirect call type check. */
    public static final int TYPE_ID = 20;

    /** Table index for indirect call. */
    public static final int TABLE_IDX = 24;

    /** Element index within the table. */
    public static final int ELEM_IDX = 28;

    // --- Call arguments ---

    /** Number of call arguments written to the args buffer. */
    public static final int ARG_COUNT = 32;

    /** Page count delta for memory.grow (dedicated field, not overloaded). */
    public static final int MEM_GROW_DELTA = 36;

    /** Pointer to the separate args buffer (each arg is i64). */
    public static final int ARGS_PTR = 40;

    /** Stack pointer limit for call depth guard (i64). */
    public static final int STACK_LIMIT = 48;

    /** Pointer to libc memmove function (i64). */
    public static final int MEMMOVE_PTR = 56;

    /** Pointer to libc memset function (i64). */
    public static final int MEMSET_PTR = 64;

    // --- Memory and globals ---

    /** Pointer to the off-heap globals buffer. */
    public static final int GLOBALS_PTR = 200;

    /** Pointer to the memory.grow upcall stub. */
    public static final int MEM_GROW_PTR = 208;

    /** Current memory page count (i32). */
    public static final int MEMORY_PAGES = 216;

    /** Current memory base address (i64). */
    public static final int MEM_BASE_ADDR = 224;

    /** Pointer to array of table buffer pointers (one i64 per table). */
    public static final int TABLE_PTRS = 232;

    /** Pointer to funcTypes array (one i32 typeIdx per function). */
    public static final int FUNC_TYPES_PTR = 240;

    // --- Trap codes (values written to TRAP_CODE offset) ---

    public static final int TRAP_NONE = 0;
    public static final int TRAP_DIV_BY_ZERO = 1;
    public static final int TRAP_INT_OVERFLOW = 2;
    public static final int TRAP_UNREACHABLE = 3;
    public static final int TRAP_TRUNC_OVERFLOW = 4;
    public static final int TRAP_OOB = 5;
    public static final int TRAP_CALL_STACK_EXHAUSTED = 6;
    public static final int TRAP_TABLE_OOB = 7;
    public static final int TRAP_UNDEFINED_ELEMENT = 8;
    public static final int TRAP_INDIRECT_CALL_TYPE_MISMATCH = 9;
    public static final int TRAP_UNINITIALIZED_ELEMENT = 10;
    public static final int TRAP_TRUNC_NAN = 11;
    public static final int TRAP_UNALIGNED_ATOMIC = 12;

    // --- NativeTable buffer layout ---
    // Each table buffer: [size:i32 @ 0][max:i32 @ 4][entries... @ 8]
    // Each entry is 16 bytes: [canonicalTypeIdx:i32][funcId:i32][funcPtr:i64]

    /** Offset of the i32 size field in a table buffer. */
    public static final int TABLE_SIZE_OFFSET = 0;

    /** Offset of the i32 max field in a table buffer. */
    public static final int TABLE_MAX_OFFSET = 4;

    /** Offset of the first table entry in a table buffer. */
    public static final int TABLE_ENTRIES_OFFSET = 8;

    /** Size of each table entry in bytes. */
    public static final int TABLE_ENTRY_SIZE = 16;

    /** Offset of canonicalTypeIdx (i32) within a table entry. */
    public static final int ENTRY_TYPE_IDX_OFFSET = 0;

    /** Offset of funcId (i32) within a table entry. */
    public static final int ENTRY_FUNC_ID_OFFSET = 4;

    /** Offset of funcPtr (i64) within a table entry. */
    public static final int ENTRY_FUNC_PTR_OFFSET = 8;

    /** Returns the byte offset for the i-th call argument within the args buffer. */
    public static int argOffset(int i) {
        return 8 * i;
    }
}
