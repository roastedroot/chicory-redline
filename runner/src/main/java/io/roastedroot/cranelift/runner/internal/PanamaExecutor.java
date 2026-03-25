package io.roastedroot.cranelift.runner.internal;

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/**
 * Helpers for mmap/mprotect via Panama FFM.
 */
final class PanamaExecutor {

    private PanamaExecutor() {}

    private static final Linker LINKER = Linker.nativeLinker();
    private static final int PROT_READ = 0x1;
    private static final int PROT_WRITE = 0x2;
    private static final int PROT_EXEC = 0x4;
    private static final int MAP_PRIVATE = 0x02;
    private static final int MAP_ANONYMOUS = 0x20;

    private static final java.lang.invoke.MethodHandle MMAP;
    private static final java.lang.invoke.MethodHandle MPROTECT;
    private static final java.lang.invoke.MethodHandle MUNMAP;

    static {
        var lookup = LINKER.defaultLookup();
        try {
            MMAP =
                    LINKER.downcallHandle(
                            lookup.find("mmap").orElseThrow(),
                            FunctionDescriptor.of(
                                    ValueLayout.ADDRESS,
                                    ValueLayout.ADDRESS,
                                    ValueLayout.JAVA_LONG,
                                    ValueLayout.JAVA_INT,
                                    ValueLayout.JAVA_INT,
                                    ValueLayout.JAVA_INT,
                                    ValueLayout.JAVA_LONG));

            MPROTECT =
                    LINKER.downcallHandle(
                            lookup.find("mprotect").orElseThrow(),
                            FunctionDescriptor.of(
                                    ValueLayout.JAVA_INT,
                                    ValueLayout.ADDRESS,
                                    ValueLayout.JAVA_LONG,
                                    ValueLayout.JAVA_INT));

            MUNMAP =
                    LINKER.downcallHandle(
                            lookup.find("munmap").orElseThrow(),
                            FunctionDescriptor.of(
                                    ValueLayout.JAVA_INT,
                                    ValueLayout.ADDRESS,
                                    ValueLayout.JAVA_LONG));
        } catch (Throwable e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    /** Allocate a writable memory region. Must call mprotectExec before executing. */
    static MemorySegment mmapCode(long size) throws Throwable {
        MemorySegment addr =
                (MemorySegment)
                        MMAP.invokeExact(
                                MemorySegment.NULL,
                                size,
                                PROT_READ | PROT_WRITE,
                                MAP_PRIVATE | MAP_ANONYMOUS,
                                -1,
                                0L);
        return addr.reinterpret(size);
    }

    /** Make a previously mmapped region executable (and remove write). */
    static void mprotectExec(MemorySegment addr, long size) throws Throwable {
        int result = (int) MPROTECT.invokeExact(addr, size, PROT_READ | PROT_EXEC);
        if (result != 0) {
            throw new RuntimeException("mprotect failed: " + result);
        }
    }

    /** Reserve address space with no access (PROT_NONE). */
    static MemorySegment mmapNoAccess(long size) throws Throwable {
        MemorySegment addr =
                (MemorySegment)
                        MMAP.invokeExact(
                                MemorySegment.NULL,
                                size,
                                0, // PROT_NONE
                                MAP_PRIVATE | MAP_ANONYMOUS,
                                -1,
                                0L);
        return addr.reinterpret(size);
    }

    /** Make a range readable and writable. */
    static void mprotectReadWrite(MemorySegment addr, long size) throws Throwable {
        int result = (int) MPROTECT.invokeExact(addr, size, PROT_READ | PROT_WRITE);
        if (result != 0) {
            throw new RuntimeException("mprotect failed: " + result);
        }
    }

    /** Unmap a memory region. */
    static void munmap(MemorySegment addr, long size) throws Throwable {
        int result = (int) MUNMAP.invokeExact(addr, size);
        if (result != 0) {
            throw new RuntimeException("munmap failed: " + result);
        }
    }
}
