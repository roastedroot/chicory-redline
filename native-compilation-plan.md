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
├── src/main/java/.../compiler/
│   └── NativeMachineFactory.java       Public API (AutoCloseable, shared Arena)
├── src/main/java/.../compiler/internal/
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

**28022 tests, 0 failures, 0 errors, 103 skipped**. Requires Java 25.
Platform: **x86_64 Linux only** (target triple hardcoded in NativeMachine).

Public API (`io.roastedroot.cranelift.compiler.NativeMachineFactory`):
```java
var factory = new NativeMachineFactory(module);
Instance.builder(module)
    .withMachineFactory(factory::compile)
    .withTableFactory(factory::createTable)
    .withGlobalFactory(factory::createGlobal)
    .withMemoryFactory(NativeMachineFactory::createMemory)
    .build();
```

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

### Real-world validation

| Project | Wasm size | Status | Notes |
|---|---|---|---|
| shootout benchmarks | ~12-37KB | PASS | All 3 benchmarks correct |
| bazel-wasm-repro (toml2json) | 237KB | PASS | 279 funcs, ~80s compilation |
| jq4j | 951KB | FAIL (9/21) | 24 funcs missing atomic opcodes |
| sqlite4j2 | 849KB | FAIL | Data-dependent codegen bug, parked until hybrid machine |

### Benchmark results (iterFact, input=1000)

```
Interpreter:   6,314 ops/s    (1x)
JVM compiled:  996,429 ops/s  (158x)
Native:        911,764 ops/s  (144x)   <- within 9% of JVM compiled
```

## Next priorities

### P1: Module split — compiler (Java 11) + runner (Java 25)

The current compiler module mixes compilation (pure Java, no Panama) with
execution (Panama FFM, Java 25). Split into two modules so the Maven plugin
and build-time compiler can run on Java 11.

See `module-split-plan.md` for detailed task breakdown.

- [ ] Split compiler/ into compiler/ (Java 11) + runner/ (Java 25)
- [ ] Maven plugin for build-time Cranelift compilation
- [ ] Documentation / quickstart for user projects

### P1: Cross-compilation for all targets

After the module split, the compiler module (Java 11) can cross-compile
for any target architecture. The target triple becomes a parameter.

- [ ] Make target triple a parameter in NativeCompiler (currently hardcoded x86_64)
- [ ] Compile for multiple targets at build time (x86_64-linux, aarch64-linux, x86_64-darwin, aarch64-darwin)
- [ ] Runtime target detection: load correct `.native` resource for current platform
- [ ] Name resources per target: `Module.x86_64-linux.native`, etc.

### P1: Hybrid Machine — automatic threshold-based dispatch

The key enabler for real-world use. Without it:
- Unsupported opcodes (atomics) fail the whole module at instantiation time
- Compilation time is prohibitive for large modules (compiles everything)
- Can't bisect the sqlite4j2 codegen bug

Tasks:
- [ ] Wire bytecode size threshold into `NativeMachineFactory` / `NativeCompiler`
- [ ] Make threshold configurable (default 8000, matching `HugeMethodLimit`)
- [ ] Functions below threshold → Chicory AOT (JVM bytecode)
- [ ] Functions above threshold → Cranelift (native)
- [ ] Functions with unsupported opcodes → fall back to Chicory AOT
- [ ] Use hybrid mode to bisect sqlite4j2 codegen bug

### P1: Rust bridge cleanup

- [ ] Guard `wasm_malloc(0)` — `std::alloc::alloc` with zero-sized layout is UB
- [ ] Clear `Session` vecs after `compile()` to free memory between compilations
- [ ] Add null guard in `b()` for clear error if builder is used after `compile()`
- [ ] Pin `interpretedFunctions` in `bridge/pom.xml` (currently `interpreterFallback=WARN`)

### P2: Cache ctxBuffer memBase writes

Currently memBase and pages are written to ctxBuffer on every `call()`.
Only update after `memory.grow` — saves two off-heap writes per call.

### P2: Fix address.wast OOB (28 skipped tests)

Bounds check uses `addr + offset + accessSize > memPages * 65536` with i32 addr.
When static offset is large (e.g. 65536), `addr + offset` overflows i32. Fix:
use i64 for the bounds computation.

### P2: Fix conversions.wast trunc overflow (35 skipped tests)

Current float-to-int trunc only checks NaN (fcmp NE x,x). Need range check:
`x < INT_MIN_as_float || x > INT_MAX_as_float -> trap`. Each trunc variant
(i32/i64 x f32/f64 x signed/unsigned) has different range bounds.

### P2: Native memory.copy/fill (optimization)

Current memory.copy/fill go through Java trampoline (native -> upcall -> Java).
Emit as native `memmove`/`memset` with inline OOB checks — no trampoline needed.

## Completed

- **Benchmark correctness**: memBase reload after calls, br_table via JumpTable
- **Factory reuse**: globalIndex reset, nativeTables clear between instances
- **invokeExact**: replaced `invokeWithArguments` + `Object[]` boxing with
  pre-adapted `MethodHandle`s and `invokeExact`. Also switched PanamaExecutor
  (mmap/mprotect/munmap) to `invokeExact`.
- **Public API**: moved `NativeMachineFactory` to `io.roastedroot.cranelift.compiler`,
  added `createMemory()` static method
- **Rust bridge**: avoided cloning `Function` in `compile()` via `std::mem::replace`
- **Compile-time failure**: unsupported opcodes now throw `ChicoryException` at
  instantiation time instead of silently producing stubs that fail at runtime.
  Test: `UnsupportedOpcodeTest`
- **wasm-tools**: replaced `wabt` test dependency with Chicory's `wasm-tools`

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
