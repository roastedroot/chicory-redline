# Chicory Native Compilation: Cranelift + Panama

## Architecture

```
Java (compilation logic)                 Rust/Wasm (thin Cranelift bridge)
========================                 =================================

NativeCompiler walks opcodes             cranelift-bridge.wasm exports:
  I32_ADD:
    a = pop()               ──────────►  emit_iadd(a, b) -> value_id
    b = pop()
    result = bridge.iadd(a,b)            internally:
    push(result)                           builder.ins().iadd(a, b)

  compile()                 ──────────►  compile() -> code bytes in linear memory
    read native bytes from bridge memory
    mmap + Panama downcall
```

Two-pass compilation: NativeAnalyzer (reachability via exitBlockDepth) produces
parallel boolean arrays, NativeEmitters (static opcode handlers) emit Cranelift IR
through EmitContext. NativeCompiler is a thin orchestrator.

## Module structure

```
cranelift-bridge/                       Thin Cranelift FFI wrapper
├── rust-src/src/lib.rs                 ~200 lines, direct FunctionBuilder calls
├── src/main/java/.../CraneliftBridge.java  Java wrapper (@WasmModuleInterface)
└── src/main/resources/cranelift-bridge.wasm  Pre-built binary (3.5MB)

cranelift-compiler/                     Native compiler + spec tests
└── src/main/java/.../compiler/
    ├── NativeMachineFactory.java       Public API (AutoCloseable, shared Arena)
    ├── NativeMachine.java              Machine impl with Panama downcalls + Cleaner
    ├── NativeCompiler.java             Thin orchestrator (analyzer → emitters)
    ├── NativeAnalyzer.java             Pre-pass: reachability via exitBlockDepth
    ├── NativeValueStack.java           Scope-aware Cranelift value ID stack
    ├── NativeEmitters.java             Static opcode emission methods
    ├── EmitContext.java                Shared state for emitters
    ├── CtxBuffer.java                  ctxBuffer layout constants (256 bytes)
    ├── NativeTable.java                Off-heap 16-byte anyfunc table entries
    ├── NativeMemory.java               Off-heap memory via Panama
    ├── NativeGlobalInstance.java        Off-heap globals buffer
    └── PanamaExecutor.java             mmap/mprotect helpers
```

## Current state

**28015 tests, 0 failures, 0 errors, 103 skipped** (92 non-simd wast files).
Only `obsolete-keywords.wast` and simd wasts remain excluded.
All happy-path (assert_return) tests pass. Requires Java 25.

Key features:
- All i32/i64/f32/f64 arithmetic/comparison/conversion opcodes (~120 total)
- Full control flow: block, loop, if/else, br, br_if, br_table, return
- Memory load/store with all widths + OOB bounds checking
- Direct calls (native-to-native via funcTable) + import upcall stubs
- CALL_INDIRECT: funcPtr+typeIdx loaded directly from 16-byte table entry
- Multi-return via argsBuffer (single-return fast path in register)
- NativeTable: 16-byte anyfunc entries with cross-module resolution
- Bulk memory: memory.copy, memory.fill, memory.init, data.drop
- Table ops: GET/SET/SIZE/GROW/FILL/COPY/INIT, ELEM.DROP
- Trap pre-checks: div-by-zero, INT_MIN/-1, unreachable, float trunc NaN
- Stack depth guard via get_stack_pointer (512KB reserve)
- Off-heap globals, tables, memory — no sync between Java and native
- Resource cleanup: Cleaner (GC safety net) + AutoCloseable (deterministic)

## Skipped tests (103 across 92 wast files — error-path only)

All skipped tests are assert_trap or validation tests. Zero happy-path failures.

| Category | Count | Root cause |
|---|---|---|
| address.wast OOB | 28 | Large static offset + addr overflows i32 bounds check |
| conversions.wast trunc | 35 | Float trunc overflow: NaN-only check, not range |
| binary.wast validation | 10 | Parser: MalformedException not thrown |
| imports.wast | 10 | Mix: OOB, setup (f32 stub fixed), validation |
| align.wast validation | 5 | Parser: MalformedException vs InvalidException |
| elem.wast | 5 | Validation/linking exceptions |
| linking.wast | 3 | Exception type + wrong result (multi-module) |
| global/memory/bulk | 4 | Message mismatch, validation |
| data.wast | 2 | InvalidException not thrown |
| start.wast | 1 | UninstantiableException vs ChicoryException |

## Next priorities

### P1: Fix address.wast OOB (28 tests)
Bounds check uses `addr + offset + accessSize > memPages * 65536` with i32 addr.
When static offset is large (e.g. 65536), `addr + offset` overflows i32. Fix:
use i64 for the bounds computation, or split into `offset + accessSize > memSize`
and `addr > memSize - offset - accessSize`.

### P1: Fix conversions.wast trunc overflow (35 tests)
Current float-to-int trunc only checks NaN (fcmp NE x,x). Need range check:
`x < INT_MIN_as_float || x > INT_MAX_as_float → trap`. Each trunc variant
(i32/i64 × f32/f64 × signed/unsigned) has different range bounds.

### P1: Hybrid Machine — JVM compiler dispatch + Cranelift function bodies

Benchmark results (iterFact, input=1000):
```
Interpreter:   6,314 ops/s    (1x)
JVM compiled:  996,429 ops/s  (158x)
Native:        911,764 ops/s  (144x)   ← within 9% of JVM compiled
```

But for trivial calls (input=5), native is 24x slower than JVM compiled due to
Panama `invokeWithArguments` + `Object[]` allocation on every `call()`. The JVM
compiler uses `invokestatic` (zero-overhead after JIT).

**Idea**: use the JVM bytecode compiler for the `Machine.call()` dispatch layer
(tableswitch → invokestatic), but have the function bodies call into Cranelift-
compiled native code. Best of both worlds:
- JVM compiler handles call dispatch, arg unboxing, result boxing (JIT-friendly)
- Cranelift handles the actual computation (native speed, no JVM bytecode limits)
- Native-to-native calls within the module stay in Cranelift (no boundary crossing)
- Only the entry point from Java crosses the Panama boundary

Alternative (simpler): optimize the current `call()` path:
- Replace `invokeWithArguments` with per-signature `invokeExact` (pre-bound handles)
- Eliminate `Object[]` allocation (typed parameters)
- Cache ctxBuffer memBase writes (only update after memory.grow)
- Estimated 10-20x improvement on small inputs

### P2: Native memory.copy/fill (optimization)
Current memory.copy/fill go through Java trampoline (native → upcall → Java).
Emit as native `memmove`/`memset` with inline OOB checks — no trampoline needed.

### P2: Real workload validation (SQLite)

Attempted to run sqlite4j2 with Cranelift native compiler (changes in
`/home/andreatp/workspace/sqlite4j2`). SQLite wasm is ~3MB, 600+ functions.
Hit issues — need to investigate:
- Compilation of large modules (many functions, large code region)
- WASI import compatibility (fd_write, proc_exit, etc.)
- Memory limits interaction with NativeMemory
- Possible unsupported opcodes in real-world C-compiled Wasm

Changes made (uncommitted in sqlite4j2):
- pom.xml: chicory 999-SNAPSHOT, Java 25, cranelift-compiler dep, surefire args
- WasmDB.java: NativeMachineFactory instead of SQLiteModule::create

### P2: Future work
- Wrap Cranelift bridge with Chicory build-time compiler (wabt/wasm-tools pattern)
- Contribute ud2 configurability to Cranelift upstream

## How to build and test

```bash
# Switch to Java 25
sdk use java 25-tem

# 1. Rebuild Rust bridge (only when lib.rs changes)
cranelift-bridge/src/main/rust/build.sh

# 2. ALWAYS run clean install after Rust rebuild (regenerates @WasmModuleInterface exports)
mvn clean install -f cranelift-bridge/pom.xml

# 3. Build and run cranelift-compiler tests
mvn clean install -f cranelift-compiler/pom.xml

# Run specific tests
mvn install -f cranelift-compiler/pom.xml -Dtest=AddTest
mvn install -f cranelift-compiler/pom.xml -Dtest=SpecV1ConstTest
```

**Important**: After rebuilding Rust, you MUST run `mvn clean install -f cranelift-bridge/pom.xml`
before building cranelift-compiler. The annotation processor reads the .wasm file at compile
time — if the .wasm changed but Java sources didn't, incremental compilation may skip
regeneration. Always use `clean install` to avoid stale state.
