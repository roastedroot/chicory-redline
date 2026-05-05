<p align="center">
  <picture>
    <img width="200" src="chicory-redline.png">
  </picture>
  <br>
  <strong>Chicory Redline</strong><br>
  Build time Compiler for Wasm in Java.
</p>

> ⚠️ **Experimental** — This project is under active development. APIs may change without notice.

## How It Works

Chicory Redline is a near drop-in replacement for [Chicory](https://github.com/dylibso/chicory)'s build-time compiler. Instead of compiling Wasm to JVM bytecode, it uses [Cranelift](https://cranelift.dev/)(the same engine used by Wasmtime) to generate native machine code at build time, then executes it at runtime through a foreign function interface.

The compilation pipeline:

1. **Build time** -- The Maven plugin feeds your `.wasm` file to Cranelift (itself compiled to Wasm and executed via Chicory) which produces native machine code for each supported platform. The generated code is bundled as resources in your JAR.
2. **Runtime** -- Native code is loaded and executed via one of two backends, selected automatically through SPI:
   - **Panama FFM** (Java 25+, zero extra dependencies, preferred when available)
   - **jffi** (Java 11+, adds ~200KB)
3. **Fallback** -- When the target architecture/OS has no precompiled native code, Redline can fall back to Chicory's pure-Java bytecode compiler transparently.

### Why not Wasmtime + JNI?

Other projects integrate Wasmtime (or similar runtimes) via JNI bindings to a platform-specific native library. Redline takes a different path: Cranelift itself is compiled to Wasm and executed at build time through Chicory. There is no native compiler to ship, link, or maintain -- and on Java 25+ (Panama) the result is **zero runtime dependencies**.

### Supported Platforms

| OS | x86_64 | aarch64 |
|---|---|---|
| Linux | yes | yes |
| macOS | yes | yes |
| Windows | yes | yes |

On any other platform the bytecode fallback (when enabled) takes over automatically.

## Safety and Performance

Chicory Redline combines the security guarantees of WebAssembly with the raw speed of native machine code, powered by [Cranelift](https://cranelift.dev/), the same production-grade compiler backend trusted by [Wasmtime](https://wasmtime.dev/).

- **Bounds-checked memory access** -- Cranelift generates bounds-checked assembly for every linear memory access, enforcing WebAssembly's sandboxing model at native speed.
- **Java-managed off-heap memory** -- All off-heap memory (linear memory, globals, code regions) is allocated through Java-managed structures (Panama `Arena` / jffi `MemoryIO`), ensuring proper lifecycle management.
- **Read-execute only code pages** -- Compiled code pages are mapped read-execute only and cannot be modified after compilation.

The result is maximum throughput with the safety properties of WebAssembly's separate heap memory model, performance that we have proven, JVM bytecode alone cannot match.

## Installation

Works on Java 11+. Uses native code when available, falls back to Chicory bytecode otherwise.

**Dependency:**

```xml
<dependency>
    <groupId>io.roastedroot</groupId>
    <artifactId>redline</artifactId>
    <version>${redline.version}</version>
</dependency>
```

**Maven plugin:**

```xml
<plugin>
    <groupId>io.roastedroot</groupId>
    <artifactId>redline-compiler-maven-plugin</artifactId>
    <version>${redline.version}</version>
    <executions>
        <execution>
            <goals>
                <goal>compile</goal>
            </goals>
            <configuration>
                <name>com.example.MyModule</name>
                <wasmFile>src/main/resources/my-module.wasm</wasmFile>
            </configuration>
        </execution>
    </executions>
</plugin>
```

## Quick Start

The Maven plugin generates a class (specified by `<name>`) with a `builder()` method. If you're familiar with Chicory's `Instance`, the usage is very similar:

```java
// The generated class provides a builder that auto-selects the best backend
try (var instance = MyModule.builder().build()) {
    var result = instance.instance().export("my_function").apply();
    System.out.println(result[0]);
}
```

Always use try-with-resources -- instances hold off-heap native code and memory that must be released.

You can check which backend is active at runtime:

```java
try (var instance = MyModule.builder().build()) {
    if (instance.isNative()) {
        System.out.println("Running native (Cranelift)");
    } else {
        System.out.println("Running bytecode (Chicory)");
    }
}
```

## Building

Prerequisites: Java 25 and Maven 3.9+ for a full build (Java 11 is enough for the compiler and jffi backend only).

```bash
mvn clean install -P panama,panama-tests,jffi,ci
```

See [CONTRIBUTING.md](CONTRIBUTING.md) for the full build guide, architecture overview, and code style guidelines.

## Roadmap

Redline aims to support every Wasm proposal that [Chicory](https://github.com/dylibso/chicory) supports. The table below tracks current progress -- checked items pass the upstream spec tests, unchecked items are not yet implemented.

- [x] Wasm MVP (core spec)
- [x] Bulk Memory Operations
- [x] Reference Types
- [x] Threads (atomics, shared memory, wait/notify)
- [ ] SIMD (v128 operations)
- [ ] Exception Handling (try/throw/tags)
- [x] Tail Call (return_call, return_call_indirect)
- [ ] Function References (call_ref, typed function references)
- [ ] GC / WasmGC (structs, arrays, i31ref)
- [ ] Multi-Memory
- [ ] Extended Constant Expressions

## Acknowledgements

Chicory Redline builds on the shoulders of two great projects:

- [Cranelift](https://cranelift.dev/) -- The production-grade code generator from the Bytecode Alliance, used here to compile Wasm to native machine code.
- [Chicory](https://github.com/dylibso/chicory) -- The pure-Java Wasm runtime that Redline extends with native compilation. Chicory powers both the bytecode fallback and the bridge that runs Cranelift itself.

## License

[Apache 2.0](https://www.apache.org/licenses/LICENSE-2.0)
