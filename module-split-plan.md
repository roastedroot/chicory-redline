# Module Split Plan: compiler (Java 11) + runner (Java 25)

## Problem

The current `compiler` module mixes two concerns at different Java version levels:
1. **Compilation**: Wasm → native bytes via Cranelift bridge (runs cranelift_bridge.wasm
   through Chicory's interpreter — pure Java, no Panama, works on Java 11)
2. **Execution**: mmap native code, create Panama FFM downcalls, execute (requires Java 25
   + `--enable-native-access`)

This means the Maven plugin (which only needs #1) can't work without Java 25, and
the `maven-plugin-plugin` ASM can't analyze Java 25 class files.

## Solution: Split into compiler + runner

```
bridge/                      (Java 11) — CraneliftBridge, cranelift_bridge.wasm
compiler/                    (Java 11) — NativeCompiler, NativeEmitters, NativeCodeSerializer
runner/                      (Java 25) — NativeMachine, PanamaExecutor, NativeMachineFactory
build-time-compiler/         (Java 11) — Config, Generator
compiler-maven-plugin/       (Java 11) — CraneliftCompilerMojo

Dependency graph:
  runner → compiler → bridge
  build-time-compiler → compiler
  compiler-maven-plugin → build-time-compiler
```

## What goes where

### compiler/ (Java 11) — compilation logic
Produces native code bytes from Wasm. No Panama, no `--enable-native-access`.

**Keep from current compiler module:**
- `NativeCompiler.java` — orchestrator (compileAll → byte[][])
- `NativeEmitters.java` — opcode emission
- `NativeAnalyzer.java` — reachability pre-pass
- `EmitContext.java` — shared emitter state
- `NativeValueStack.java` — scope-aware value ID stack
- `CtxBuffer.java` — layout constants (shared with runner)

**New:**
- `NativeCodeSerializer.java` — serialize/deserialize byte[][]

**Package**: `io.roastedroot.cranelift.compiler.internal`
**Public API**: `io.roastedroot.cranelift.compiler.NativeCodeSerializer`

### runner/ (Java 25) — runtime execution
mmaps pre-compiled (or JIT-compiled) native code, creates Panama downcalls, executes.

**Move from current compiler module:**
- `NativeMachine.java` — Machine impl with Panama downcalls
- `PanamaExecutor.java` — mmap/mprotect/munmap
- `NativeMemory.java` — off-heap memory via Panama
- `NativeTable.java` — off-heap 16-byte anyfunc entries
- `NativeGlobalInstance.java` — off-heap globals buffer
- `NativeMachineFactory.java` — public API

**Package**: `io.roastedroot.cranelift.runner.internal`
**Public API**: `io.roastedroot.cranelift.runner.NativeMachineFactory`

**Depends on compiler module for:**
- `CtxBuffer` (layout constants)
- `NativeCompiler.buildCanonicalTypeMap()` (canonical type indices)
- `NativeCompiler` + `CraneliftBridge` (for JIT compilation path)
- `NativeCodeSerializer` (for loading pre-compiled code)

### bridge/ (Java 11) — unchanged
Already a separate module. CraneliftBridge wrapping cranelift_bridge.wasm.

### build-time-compiler/ (Java 11) — unchanged
Config + Generator. Depends on compiler module only.

### compiler-maven-plugin/ (Java 11) — unchanged
CraneliftCompilerMojo. Depends on build-time-compiler.

## Tasks

- [ ] Create runner/ module (pom.xml with Java 25, --enable-native-access)
- [ ] Move Panama-dependent classes from compiler/ to runner/
- [ ] Update packages (compiler.internal → runner.internal for moved classes)
- [ ] Update imports across all moved files
- [ ] Set compiler/ module to Java 11 (maven.compiler.release=11)
- [ ] Verify bridge/ works with Java 11
- [ ] Move tests: spec tests + unit tests → runner module
- [ ] Keep compiler-only tests in compiler module (if any)
- [ ] Update NativeMachineFactory imports in build-time-compiler generated code
- [ ] Update parent pom module order
- [ ] Full build + test (28022 tests, 0 failures)
- [ ] Update native-compilation-plan.md

## Risks / Considerations

- **CtxBuffer shared**: Used by both compiler (to emit correct offsets in native code)
  and runner (to read/write at runtime). Lives in compiler, runner imports it.
- **NativeCompiler in runner**: NativeMachine's JIT path calls NativeCompiler.compileAll().
  Runner depends on compiler module, so this works.
- **Generated source code**: build-time-compiler generates Java source referencing
  NativeMachineFactory. Need to update the package in generated code to
  `io.roastedroot.cranelift.runner.NativeMachineFactory`.
- **Cross-compilation**: After this split, the compiler module can cross-compile for
  any target (x86_64, aarch64) since it just produces bytes. The runner module
  handles platform-specific mmap/execution. Target triple is a compiler parameter.
