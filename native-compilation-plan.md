# Cranelift4J: Native Compilation Plan

## Architecture

```
Java (compilation logic)                 Rust/Wasm (thin Cranelift bridge)
========================                 =================================

NativeCompiler walks opcodes             cranelift_bridge.wasm exports:
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

### Hybrid strategy: JVM bytecode + Cranelift based on HugeMethodLimit

HotSpot `HugeMethodLimit` = **8000 bytes** of bytecode (`code_length` in the
class file Code attribute). Above this, C1/C2 give up optimizing the method.
Reference: https://github.com/search?q=repo%3Aopenjdk%2Fjdk+HugeMethodLimit&type=code

- **Below threshold**: Use Chicory's default AOT compiler (Wasm -> JVM bytecode).
  HotSpot JIT optimizes these well.
- **Above threshold**: Use Cranelift4J (Wasm -> native assembly). HotSpot would
  give up anyway, so compile directly to machine code.

Cranelift4J is a targeted complement, not a replacement.

## Module structure

```
wasm-build/                             Rust Cranelift FFI wrapper
├── Cargo.toml
└── src/lib.rs                          ~1430 lines, flat Wasm exports

bridge/                                 Java bridge module
├── pom.xml
└── src/main/java/.../bridge/
    └── CraneliftBridge.java            Java wrapper (@WasmModuleInterface)

compiler/                               Native compiler + spec tests
├── pom.xml
├── src/main/java/.../compiler/internal/
│   ├── NativeMachineFactory.java       Public API (AutoCloseable, shared Arena)
│   ├── NativeMachine.java              Machine impl with Panama downcalls + Cleaner
│   ├── NativeCompiler.java             Thin orchestrator (analyzer -> emitters)
│   ├── NativeAnalyzer.java             Pre-pass: reachability via exitBlockDepth
│   ├── NativeValueStack.java           Scope-aware Cranelift value ID stack
│   ├── NativeEmitters.java             Static opcode emission methods
│   ├── EmitContext.java                Shared state for emitters
│   ├── CtxBuffer.java                  ctxBuffer layout constants (256 bytes)
│   ├── NativeTable.java                Off-heap 16-byte anyfunc table entries
│   ├── NativeMemory.java               Off-heap memory via Panama
│   ├── NativeGlobalInstance.java        Off-heap globals buffer
│   └── PanamaExecutor.java             mmap/mprotect helpers
└── src/test/java/
    ├── io/roastedroot/.../internal/     Unit tests (AddTest, CallTest, etc.)
    └── com/dylibso/chicory/testing/     Spec test harness (NativeInstanceBuilder, etc.)

cranelift_bridge.wasm                   Pre-built Wasm binary (~5MB, root level)
```

## Current state

**28015 tests, 0 failures, 0 errors, 103 skipped**. Requires Java 25.

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
- Proper `wasm_malloc`/`wasm_free` for Wasm linear memory allocation (Rust global allocator)

### Skipped tests (103 — error-path only)

All skipped tests are assert_trap or validation tests. Zero happy-path failures.

| Category | Count | Root cause |
|---|---|---|
| conversions.wast trunc | 35 | Float trunc overflow: NaN-only check, not range |
| address.wast OOB | 28 | Large static offset + addr overflows i32 bounds check |
| binary.wast validation | 10 | Parser: MalformedException not thrown |
| imports.wast | 10 | Mix: OOB, setup, validation |
| align.wast validation | 5 | Parser: MalformedException vs InvalidException |
| elem.wast | 5 | Validation/linking exceptions |
| global/memory/bulk | 4 | Message mismatch, validation |
| linking.wast | 3 | Exception type + wrong result (multi-module) |
| data.wast | 2 | InvalidException not thrown |
| start.wast | 1 | UninstantiableException vs ChicoryException |

## Next priorities

### P1: Hybrid Machine — automatic threshold-based dispatch

- [ ] Wire bytecode size threshold into `NativeMachineFactory` / `NativeCompiler`
- [ ] Make threshold configurable (default 8000, matching `HugeMethodLimit`)
- [ ] Benchmark: compare hybrid vs pure-Cranelift vs pure-JVM on real workloads

### P1: Fix address.wast OOB (28 tests)

Bounds check uses `addr + offset + accessSize > memPages * 65536` with i32 addr.
When static offset is large (e.g. 65536), `addr + offset` overflows i32. Fix:
use i64 for the bounds computation, or split into `offset + accessSize > memSize`
and `addr > memSize - offset - accessSize`.

### P1: Fix conversions.wast trunc overflow (35 tests)

Current float-to-int trunc only checks NaN (fcmp NE x,x). Need range check:
`x < INT_MIN_as_float || x > INT_MAX_as_float -> trap`. Each trunc variant
(i32/i64 x f32/f64 x signed/unsigned) has different range bounds.

### P1: Rust bridge cleanup

- [ ] Guard `wasm_malloc(0)` — `std::alloc::alloc` with zero-sized layout is UB
- [x] Avoid cloning `Function` in `compile()` — uses `std::mem::replace` to take
      ownership instead of deep-copying the IR
- [ ] Clear `Session` vecs after `compile()` to free memory between compilations
- [ ] Add null guard in `b()` for clear error if builder is used after `compile()`
- [ ] Pin `interpretedFunctions` in `bridge/pom.xml` (currently `interpreterFallback=WARN`)

### P1: Optimize Panama call() overhead

For trivial calls (input=5), native is 24x slower than JVM compiled due to
Panama `invokeWithArguments` + `Object[]` allocation on every `call()`.

- [ ] Replace `invokeWithArguments` with per-signature `invokeExact` (pre-bound handles)
- [ ] Eliminate `Object[]` allocation (typed parameters)
- [ ] Cache ctxBuffer memBase writes (only update after memory.grow)

Benchmark results (iterFact, input=1000):
```
Interpreter:   6,314 ops/s    (1x)
JVM compiled:  996,429 ops/s  (158x)
Native:        911,764 ops/s  (144x)   <- within 9% of JVM compiled
```

### P2: Native memory.copy/fill (optimization)

Current memory.copy/fill go through Java trampoline (native -> upcall -> Java).
Emit as native `memmove`/`memset` with inline OOB checks — no trampoline needed.

### P2: Real workload validation (SQLite)

SQLite wasm is ~3MB, 600+ functions. Need to validate:
- Compilation of large modules (many functions, large code region)
- WASI import compatibility (fd_write, proc_exit, etc.)
- Memory limits interaction with NativeMemory
- Possible unsupported opcodes in real-world C-compiled Wasm

## How to build and test

```bash
# Requires Java 25
java -version  # should show 25

# 1. Rebuild Rust bridge (only when lib.rs changes)
cd wasm-build && cargo build --target wasm32-wasip1 --release
cp target/wasm32-wasip1/release/cranelift_bridge.wasm ../cranelift_bridge.wasm
cd ..

# 2. Full build (from repo root)
mvn clean install

# Run specific tests
mvn install -pl compiler -Dtest=AddTest
mvn install -pl compiler -Dtest=SpecV1ConstTest
```

**Important**: After rebuilding Rust, you MUST run `mvn clean install` (not just
the compiler module). The annotation processor reads the .wasm file at compile
time — if the .wasm changed but Java sources didn't, incremental compilation may
skip regeneration. Always use `clean install` to avoid stale state.
