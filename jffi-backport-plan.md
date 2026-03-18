# Cranelift Compiler: jffi Backport Plan (Java 11+)

## Problem

The current native compiler requires Java 25 (Panama FFI). This limits adoption
to bleeding-edge JVMs. Most production deployments use Java 11, 17, or 21 LTS.

## Solution

Abstract the native calling layer behind a `NativeRuntime` interface with two
implementations:
- `PanamaNativeRuntime` — current code, Java 25+ (no dependencies)
- `JffiNativeRuntime` — Java 11+ via jffi (~200KB dependency)

Everything else (Cranelift compilation, NativeTable, NativeMemory, NativeGlobalInstance,
NativeMachine logic) stays unchanged. Only the "call function pointer" and "mmap
executable memory" parts are abstracted.

## Why jffi

| Requirement | jffi provides |
|---|---|
| mmap + mprotect(PROT_EXEC) | `com.kenai.jffi.MemoryIO` + `PageManager` |
| Call function pointer | `com.kenai.jffi.Invoker` with typed signatures |
| Create upcall stubs | `com.kenai.jffi.ClosureManager` |
| Platform coverage | Linux x86_64/aarch64, macOS x86_64/aarch64, Windows x86_64 |
| Size | ~200KB JAR (ships libffi inside) |
| Maturity | 17 years, maintained by JRuby team (Charles Nutter) |
| Java version | 8+ |

### Alternatives considered

| Approach | Verdict |
|---|---|
| Panama with --enable-preview (Java 22-24) | Non-starter for production libraries |
| JNI + hand-written C shim | Works but need native build infra per platform |
| Runtime-generated ELF/Mach-O + System.load() | Zero-dep but complex (ELF/Mach-O/PE generation + JNI calling convention wrappers) |
| JNA | Built on libffi but heavier (~1.5MB), higher-level than we need |
| JNR-FFI | Built on jffi, adds unnecessary abstraction layer |

## Interface design

```java
public interface NativeRuntime {

    // Allocate executable memory and copy code into it
    long mmapCode(byte[] code, int length);

    // Free executable memory
    void munmapCode(long address, long length);

    // Call a compiled function: funcPtr(ctxPtr, memoryBase) -> result
    long callFunc(long funcPtr, long ctxPtr, long memoryBase);

    // Create an upcall stub that calls back into Java
    // Used for import function trampolines
    long createUpcallStub(MethodHandle target, FunctionType type);

    // Free an upcall stub
    void freeUpcallStub(long stub);
}
```

## Implementation plan

### Phase 1: Extract NativeRuntime interface

Refactor current Panama-specific code out of NativeMachine/PanamaExecutor into
`PanamaNativeRuntime`. No behavior change. All tests still pass.

Files to change:
- New: `NativeRuntime.java` (interface)
- New: `PanamaNativeRuntime.java` (extracted from PanamaExecutor + NativeMachine)
- Modified: `NativeMachine.java` (delegate to NativeRuntime instead of direct Panama calls)
- Modified: `PanamaExecutor.java` (may be absorbed into PanamaNativeRuntime)

### Phase 2: Implement JffiNativeRuntime

New module: `cranelift-compiler-jffi/` (separate module to keep jffi dependency optional)

```xml
<dependency>
    <groupId>com.github.jnr</groupId>
    <artifactId>jffi</artifactId>
    <version>1.3.13</version>
</dependency>
```

Implementation sketch:

```java
public class JffiNativeRuntime implements NativeRuntime {

    @Override
    public long mmapCode(byte[] code, int length) {
        // jffi's MemoryIO can allocate + mprotect
        long addr = MemoryIO.getInstance().allocateMemory(length, false);
        MemoryIO.getInstance().putByteArray(addr, code, 0, length);
        // Mark as executable
        PageManager.getInstance().protectPages(addr, length,
            PageManager.PROT_READ | PageManager.PROT_EXEC);
        return addr;
    }

    @Override
    public long callFunc(long funcPtr, long ctxPtr, long memoryBase) {
        // jffi Invoker with POINTER,POINTER -> SINT64
        return Invoker.getInstance().invokeN2(
            new Function(funcPtr, CallContext.getCallContext(
                Type.SINT64, Type.POINTER, Type.POINTER)),
            ctxPtr, memoryBase);
    }

    @Override
    public long createUpcallStub(MethodHandle target, FunctionType type) {
        // Use jffi ClosureManager to create a native callback
        // that invokes the MethodHandle
        ...
    }
}
```

### Phase 3: Off-heap memory without Panama

NativeMemory, NativeTable, NativeGlobalInstance currently use `Arena` and
`MemorySegment` for off-heap buffers. The jffi module needs its own off-heap
abstraction. Options:

| Panama | jffi equivalent |
|---|---|
| `Arena.ofConfined()` | Manual `MemoryIO.allocateMemory()` + tracking |
| `MemorySegment.set(JAVA_LONG, offset, val)` | `MemoryIO.putLong(addr + offset, val)` |
| `MemorySegment.get(JAVA_LONG, offset)` | `MemoryIO.getLong(addr + offset)` |
| `Arena.close()` | `MemoryIO.freeMemory(addr)` for each allocation |

Alternative: use `sun.misc.Unsafe` for off-heap (available on all JVMs, just
discouraged). `Unsafe.allocateMemory()` + `putLong()`/`getLong()` is functionally
identical to `MemorySegment` for our use case. Many production libraries still
use it (Netty, Cassandra, Spark).

## Module structure: Two separate modules

MRJAR won't work here — the jffi module has a native dependency that would get
pulled in even on Java 25+ where it's not needed. Two separate modules with
shared core logic:

```
cranelift-shared/                 Shared compilation logic (Java 11+, zero deps)
├── NativeRuntime.java            Interface
├── NativeCompiler.java           Walks opcodes, emits Cranelift IR
├── NativeAnalyzer.java           Pre-pass: reachability
├── NativeEmitters.java           Opcode emission
├── EmitContext.java              Shared state
├── CtxBuffer.java                ctxBuffer layout
└── ...                           Everything that doesn't touch Panama or jffi

cranelift-compiler/               Java 25+ (Panama, zero dependencies)
├── PanamaNativeRuntime.java      Panama implementation
├── NativeMachine.java            Machine impl using PanamaNativeRuntime
├── NativeTable.java              Off-heap table via MemorySegment
├── NativeMemory.java             Off-heap memory via MemorySegment
├── NativeGlobalInstance.java     Off-heap globals via MemorySegment
├── PanamaExecutor.java           mmap/mprotect helpers
└── pom.xml                       depends on cranelift-shared (no jffi)

cranelift-compiler-jffi/          Java 11+ (jffi dependency)
├── JffiNativeRuntime.java        jffi implementation
├── JffiNativeMachine.java        Machine impl using JffiNativeRuntime
├── JffiNativeTable.java          Off-heap table via MemoryIO/Unsafe
├── JffiNativeMemory.java         Off-heap memory via MemoryIO/Unsafe
├── JffiNativeGlobalInstance.java Off-heap globals via MemoryIO/Unsafe
└── pom.xml                       depends on cranelift-shared + jffi
```

Users pick one based on their Java version:
```xml
<!-- Java 25+ (zero deps, Panama) -->
<dependency>
    <groupId>com.dylibso.chicory</groupId>
    <artifactId>cranelift-compiler</artifactId>
</dependency>

<!-- Java 11+ (jffi) -->
<dependency>
    <groupId>com.dylibso.chicory</groupId>
    <artifactId>cranelift-compiler-jffi</artifactId>
</dependency>
```

Both modules share all compilation logic via `cranelift-shared`. The only
difference is how they call into native code and manage off-heap memory.

## Risks and open questions

1. **jffi upcall stubs**: need to verify ClosureManager supports our calling
   convention (2 pointer args → long return). JRuby uses it heavily, so likely yes.

2. **Off-heap memory API**: choose between jffi MemoryIO vs Unsafe. Unsafe is
   simpler (no extra dep) but may be restricted in future JVMs. jffi MemoryIO
   keeps everything in one dependency.

3. **Performance**: jffi Invoker vs Panama Linker — both go through libffi/native
   boundary. Need to benchmark. Expect comparable overhead.

4. **Arena lifecycle**: Panama Arena provides scoped deallocation. With jffi/Unsafe
   we manage individual allocations manually. The Cleaner + AutoCloseable pattern
   still works — just more bookkeeping in the close() method.
