# Chicory Redline

<p align="center">
  <picture>
    <img width="200" src="chicory-redline.png">
  </picture>
</p>

Build time Compiler for Wasm in Java.

> **Warning**
> This project is experimental and under active development. APIs may change without notice.

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

## Safety

Chicory Redline is the performance-oriented companion to [Chicory](https://github.com/dylibso/chicory). It is designed for situations where JVM bytecode throughput is not enough and a fully sandboxed, self-contained environment is not the top priority.

Cranelift generates bounds-checked assembly for every linear memory access, and all off-heap memory (linear memory, globals, code regions) is allocated through Java-managed structures (Panama `Arena` / jffi `MemoryIO`). Code pages are mapped read-execute only -- they cannot be modified after compilation.

That said, the compiled code runs **outside the JVM**. This means you give up some runtime safety guarantees that the JVM normally provides:

- A bug in the Cranelift compiler could produce incorrect machine code that crashes the JVM process (SIGSEGV) rather than throwing a Java exception.
- There is no OS-level sandbox around the generated code -- safety relies entirely on the correctness of Cranelift's bounds checks and code generation.

If your use case requires a fully secure and self-contained execution environment, use Chicory's pure-Java bytecode compiler instead. Redline is for when you need the maximum performance of a native compiler and accept the trade-off.

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
mvn clean install
```

See [CONTRIBUTING.md](CONTRIBUTING.md) for the full build guide, architecture overview, and code style guidelines.

## Acknowledgements

Chicory Redline builds on the shoulders of two great projects:

- [Cranelift](https://cranelift.dev/) -- The production-grade code generator from the Bytecode Alliance, used here to compile Wasm to native machine code.
- [Chicory](https://github.com/dylibso/chicory) -- The pure-Java Wasm runtime that Redline extends with native compilation. Chicory powers both the bytecode fallback and the bridge that runs Cranelift itself.

## License

[Apache 2.0](https://www.apache.org/licenses/LICENSE-2.0)
