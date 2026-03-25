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

bridge/                                 Java bridge module (Java 11)
├── pom.xml
└── src/main/java/.../bridge/
    └── CraneliftBridge.java            Java wrapper (@WasmModuleInterface)

compiler/                               Compilation logic (Java 11, no Panama)
├── pom.xml
├── src/main/java/.../compiler/internal/
│   ├── NativeCompiler.java             Thin orchestrator (analyzer -> emitters)
│   ├── NativeAnalyzer.java             Pre-pass: reachability via exitBlockDepth
│   ├── NativeValueStack.java           Scope-aware Cranelift value ID stack
│   ├── NativeEmitters.java             Static opcode emission methods
│   ├── EmitContext.java                Shared state for emitters
│   └── CtxBuffer.java                  ctxBuffer layout constants (256 bytes)
└── src/test/java/                      UnsupportedOpcodeTest

runner/                                 Runtime execution (Java 25, Panama FFM)
├── pom.xml
├── src/main/java/.../runner/
│   └── NativeMachineFactory.java       Public API (AutoCloseable, shared Arena)
├── src/main/java/.../runner/internal/
│   ├── NativeMachine.java              Machine impl with Panama downcalls + Cleaner
│   ├── NativeTable.java                Off-heap 16-byte anyfunc table entries
│   ├── NativeMemory.java               Off-heap memory via Panama
│   ├── NativeGlobalInstance.java        Off-heap globals buffer
│   └── PanamaExecutor.java             mmap/mprotect helpers
└── src/test/java/                      Unit tests + spec tests (~28020 tests)

build-time-compiler/                    Build-time API (Java 11)
compiler-maven-plugin/                  Maven plugin (Java 11)

cranelift_bridge.wasm                   Pre-built Wasm binary (~5MB, root level)
```

## Current state

**28022 tests, 0 failures, 0 errors, 103 skipped**. Requires Java 25 for runner.
Compiler module is Java 11 — usable by Maven plugin on any JDK >= 11.
Cross-compilation: all 6 targets by default (Linux/macOS/Windows × x86_64/aarch64).
CI: ubuntu x86_64, ubuntu arm64, macOS arm64, Windows x86_64.

Public API (`io.roastedroot.cranelift.runner.NativeMachineFactory`):
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
| prism (Ruby parser) | 564KB | PASS | 10x faster than Chicory AOT, build-time compilation via Maven plugin |
| sqlite4j2 | 849KB | PASS | 4x faster than Chicory AOT, build-time compilation via Maven plugin |

### Benchmark results (iterFact, input=1000)

```
Interpreter:   6,314 ops/s    (1x)
JVM compiled:  996,429 ops/s  (158x)
Native:        911,764 ops/s  (144x)   <- within 9% of JVM compiled
```

## Next priorities

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

### P2: NativeMemory mmap reservation leak

The mmap/mprotect approach reserves up to 4GB virtual address space per instance
(PROT_NONE, no physical RAM committed). When instances are never explicitly
closed (e.g. generated code creates factory as local var), the reservations
accumulate. Cleaner fires on GC but GC doesn't feel off-heap pressure.

Current mitigations:
- NativeMachine CleanupAction calls nativeMemory.close() (deterministic if factory.close() called)
- NativeMachineFactory.close() closes arena + machine
- Cleaner as safety net for final cleanup

Remaining issue: generated code and test infrastructure don't call close().
Options: make generated code expose factory for closing, add Instance.close()
to Chicory upstream, or use Arena.ofAuto() for GC-aware native memory tracking.

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
- **Compile-time failure**: all compilation errors (unsupported opcodes, bridge
  crashes) now throw `ChicoryException` at instantiation time instead of silently
  producing stubs that fail at runtime. Test: `UnsupportedOpcodeTest`
- **wasm-tools**: replaced `wabt` test dependency with Chicory's `wasm-tools`
- **Module split**: compiler (Java 11) + runner (Java 25). Compiler has no Panama
  dependency — usable by Maven plugin on any JDK >= 11. NativeEmitters converted
  to Java 11 syntax (no switch expressions, no instanceof patterns, no Stream.toList)
- **Maven plugin**: build-time compilation via `cranelift-compiler-maven-plugin`.
  Generates `.native` (serialized machine code) + `.meta` (stripped wasm) +
  Java source wrapper. Validated on prism (564KB wasm, 10x faster than Chicory AOT).
- **Eager block sealing**: seal BLOCK/IF/ELSE merge blocks immediately after all
  predecessors are known, matching wasmtime. Fixes SSA builder panic when
  `use_var` is called on unsealed blocks with multiple predecessors.
- **Parallel compilation**: `compileAll()` partitions functions across
  `availableProcessors()/2` threads, each with its own CraneliftBridge instance.
  Shared static thread pool avoids resource exhaustion under concurrent use.
- **mmap/mprotect NativeMemory**: reserves full max address range upfront with
  `mmap(PROT_NONE)`, commits pages with `mprotect(PROT_READ|PROT_WRITE)`.
  `grow()` is zero-copy (just mprotect more range), base address never changes.
  Cleaner-based munmap on GC + NativeMachine CleanupAction for deterministic chain.
  Validated on sqlite4j2 (4x perf improvement, memory-grow-heavy workload).
- **CraneliftBridge leak fix**: `compileAll()` now closes bridges via
  try-with-resources after each compilation chunk. Prevents ~6MB on-heap leak
  per bridge instance (cranelift_bridge.wasm ByteArrayMemory).
- **NativeMachineFactory cleanup**: `close()` now also closes the shared Arena
  as safety net (idempotent with NativeMachine's CleanupAction).
- **Cross-compilation**: `CraneliftTarget` utility with 6 target triples,
  `detectHost()` for runtime platform detection, `resourceSuffix()` for
  resource naming. Generator compiles all targets in parallel, produces
  per-platform `.native` files. Maven plugin accepts `<targets>` to restrict.
  Generated code auto-detects platform and loads matching resource.

## How to build and test

```bash
# Requires Java 25 (for runner module; compiler/bridge only need Java 11)
java -version  # should show 25

# 1. Rebuild Rust bridge (only when lib.rs changes)
cd wasm-build && cargo build --target wasm32-wasip1 --release
cp target/wasm32-wasip1/release/cranelift_bridge.wasm ../cranelift_bridge.wasm
cd ..

# 2. Full build (from repo root)
mvn clean install

# Run specific tests (spec tests and unit tests are in runner module)
mvn clean install -DskipTests && mvn install -pl runner -Dtest=AddTest
mvn clean install -DskipTests && mvn install -pl runner -Dtest=SpecV1ConstTest

# Compiler-only tests (UnsupportedOpcodeTest)
mvn clean install -DskipTests && mvn install -pl compiler
```

**Important**: Always use `mvn clean install` (not `-pl`) for the initial build.
The annotation processor reads the .wasm file at compile time — incremental
builds may skip regeneration. Also, `-pl runner` does NOT rebuild the compiler
module, so changes in compiler/ require a full rebuild first.
