# Contributing to Chicory Redline

## Architecture

```
Java (compilation logic)                 Rust/Wasm (thin Cranelift bridge)
========================                 =================================

NativeCompiler walks opcodes             cranelift_bridge.wasm exports:
  I32_ADD:
    a = pop()               ──────────>  emit_iadd(a, b) -> value_id
    b = pop()
    result = bridge.iadd(a,b)            internally:
    push(result)                           builder.ins().iadd(a, b)

  compile()                 ──────────>  compile() -> code bytes in linear memory
    read native bytes from bridge memory
    mmap + downcall (Panama or jffi)
```

Two-pass compilation: NativeAnalyzer (reachability via exitBlockDepth) produces
parallel boolean arrays, NativeEmitters (static opcode handlers) emit Cranelift IR
through EmitContext. NativeCompiler is a thin orchestrator.

All Wasm functions are compiled to native machine code via Cranelift.
Chicory Redline compiles the entire module -- no interpreter fallback at runtime.

### Module structure

| Module | Java | Purpose |
|---|---|---|
| `bridge/` | 11 | `RedlineBridge` wrapping `cranelift_bridge.wasm` via Chicory |
| `compiler/` | 11 | `NativeCompiler`, `NativeEmitters`, `CtxBuffer`, SPI interface (`RedlineMachineFactoryProvider`) |
| `runner/` | 25 | Panama FFM backend: `NativeMachineFactory`, `NativeMachine`, `PanamaExecutor`, `NativeMemory` |
| `runner-jffi/` | 11 | jffi backend: `JffiNativeMachineFactory`, `JffiNativeMachine`, `JffiNativeMemory` |
| `build-time-compiler/` | 11 | `Config` + `Generator` (JavaParser) for Maven plugin |
| `compiler-maven-plugin/` | 11 | `RedlineCompilerMojo` |
| `integration-tests/` | - | Maven Invoker IT (toml2json) |
| `jmh/` | 25 | JMH benchmarks (Chicory bytecode vs Cranelift Panama vs Cranelift jffi) |
| `wasm/` | - | Shared wasm files + toml2json Rust source |

### Runner backends

Two runner backends provide the same functionality via SPI (`RedlineMachineFactoryProvider`):

| Backend | Module | Java | Dependency | How it works |
|---|---|---|---|---|
| **Panama** | `runner/` | 25+ | none (uses `java.lang.foreign`) | `Linker.downcallHandle()`, `Arena`, `MemorySegment` |
| **jffi** | `runner-jffi/` | 11+ | `jffi` (~200KB) | `Invoker.invokeN*()`, `MemoryIO`, `PageManager` |

Users pick one dependency -- the SPI discovers it automatically:

```xml
<!-- Java 25+ (zero deps, Panama FFM) -->
<dependency>
    <groupId>io.roastedroot</groupId>
    <artifactId>redline-runner</artifactId>
</dependency>

<!-- Java 11+ (jffi ~200KB) -->
<dependency>
    <groupId>io.roastedroot</groupId>
    <artifactId>redline-runner-jffi</artifactId>
</dependency>
```

### Maven profiles

| Profile | Modules | Activation |
|---|---|---|
| `panama` | `runner` | Auto on JDK 25+ |
| `jffi` | `redline`, `runner-jffi` | Manual (`-P jffi`) |
| `ci` | `runner-tests`, `runner-jffi-tests`, `integration-tests`, `jmh` | Manual (`-P ci`) |
| `release` | GPG signing, javadoc, source jars, Maven Central deploy | Manual (`-P release`) |

Base modules (api, bridge, compiler, build-time-compiler, compiler-maven-plugin) always build regardless of profiles.

Test modules (`runner-tests`, `runner-jffi-tests`) live in the `ci` profile to keep them out of the release reactor. Activate `-P ci` to run the spec tests locally.

### Key packages

- `io.roastedroot.redline.bridge` -- RedlineBridge
- `io.roastedroot.redline.compiler` -- `RedlineInstance`, `RedlineMachineFactoryProvider` (SPI)
- `io.roastedroot.redline.compiler.internal` -- NativeCompiler, NativeEmitters, CtxBuffer
- `io.roastedroot.redline.runner` -- NativeMachineFactory, PanamaMachineFactoryProvider (public API)
- `io.roastedroot.redline.runner.internal` -- NativeMachine, PanamaExecutor, NativeMemory
- `io.roastedroot.redline.runner` (jffi) -- JffiNativeMachineFactory, JffiMachineFactoryProvider
- `io.roastedroot.redline.runner.internal` (jffi) -- JffiNativeMachine, JffiNativeMemory

## Building

### Prerequisites

- Java 25 (for full build with Panama runner; compiler/bridge/jffi only need Java 11)
- Maven 3.9+

### Full build

```bash
mvn clean install -P panama,jffi,ci
```

This runs checkstyle, spotless, compilation, and ~28k spec tests on both runners.

**Important**: Always use `mvn clean install` (not `-pl`) for the initial build.
`-pl runner` does NOT rebuild the compiler module, so changes in compiler/
require a full rebuild first.

### Build only the jffi backend

```bash
mvn clean install -P jffi,ci
```

### Rebuild the Rust bridge (only when lib.rs changes)

```bash
cd wasm-build && cargo build --target wasm32-wasip1 --release
cp target/wasm32-wasip1/release/cranelift_bridge.wasm ../cranelift_bridge.wasm
cd ..
```

### Running specific tests

```bash
# Spec tests and unit tests (runner-tests module)
mvn clean install -DskipTests && mvn install -pl runner-tests -Dtest=AddTest

# Compiler-only tests (UnsupportedOpcodeTest)
mvn clean install -DskipTests && mvn install -pl compiler

# Integration tests (toml2json)
mvn clean install -DskipTests && mvn install -pl integration-tests

# JMH benchmarks (Panama vs jffi vs Chicory bytecode)
mvn clean install -P panama,jffi,ci -DskipTests && jmh/run.sh
```

## Code style

- Checkstyle enforces: `NeedBraces`, no `IllegalCatch`, `PackageDeclaration` matching directory, `JavadocContentLocation`
- Compiler uses `-Werror` so deprecation warnings are fatal
- Java 11 modules (bridge, compiler, build-time-compiler, plugin, runner-jffi): no switch expressions, no `instanceof` patterns, no `Stream.toList()` -- but `var` is fine (Java 10+)
- Tests use `com.dylibso.chicory.tools.wasm.Wat2Wasm` (wasm-tools) instead of wabt

## Public API

### SPI-based (backend-independent, recommended)

Generated by Maven plugin (`redline-compiler-maven-plugin`):

```java
try (var ni = MyModule.builder().build()) {
    var result = ni.instance().export("func").apply();
}
```

Direct API (`io.roastedroot.redline.api.RedlineInstance`):

```java
// Runtime compilation (auto-discovers backend via SPI)
try (var ni = RedlineInstance.builder(module).build()) {
    ni.instance().export("func").apply();
}

// With precompiled code
try (var ni = RedlineInstance.builder(module)
        .withPrecompiledCode(precompiledCode)
        .withImportValues(imports)
        .build()) {
    ni.instance().export("func").apply();
}
```

### Backend-specific (when you need explicit control)

```java
// Panama (Java 25+)
try (var ni = NativeMachineFactory.builder(module).build()) {
    ni.instance().export("func").apply();
}

// jffi (Java 11+)
try (var ni = JffiNativeMachineFactory.builder(module).build()) {
    ni.instance().export("func").apply();
}
```

Always close instances (try-with-resources) -- they hold off-heap native code and memory.
