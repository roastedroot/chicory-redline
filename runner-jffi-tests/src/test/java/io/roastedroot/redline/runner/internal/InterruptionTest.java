package io.roastedroot.redline.runner.internal;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dylibso.chicory.corpus.CorpusResources;
import com.dylibso.chicory.wasm.ChicoryException;
import com.dylibso.chicory.wasm.Parser;
import io.roastedroot.redline.api.RedlineInstance;
import io.roastedroot.redline.api.RedlineTarget;
import io.roastedroot.redline.compiler.internal.NativeCompiler;
import io.roastedroot.redline.runner.JffiNativeMachineFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

public class InterruptionTest {

    @Test
    public void shouldInterruptLoop() throws InterruptedException {
        try (var ni = buildInstance("compiled/infinite-loop.c.wasm")) {
            var function = ni.instance().export("run");
            AtomicBoolean interrupted = new AtomicBoolean();
            Thread thread =
                    new Thread(
                            () -> {
                                var e = assertThrows(ChicoryException.class, function::apply);
                                assertEquals("interrupted", e.getMessage());
                                interrupted.set(true);
                            });
            thread.start();
            Thread.sleep(100);
            ni.requestInterrupt();
            SECONDS.timedJoin(thread, 10);
            assertTrue(interrupted.get());
        }
    }

    @Test
    public void shouldInterruptCall() throws InterruptedException {
        try (var ni = buildInstance("compiled/power.c.wasm")) {
            var function = ni.instance().export("run");
            AtomicBoolean interrupted = new AtomicBoolean();
            Thread thread =
                    new Thread(
                            () -> {
                                var e =
                                        assertThrows(
                                                ChicoryException.class, () -> function.apply(100));
                                assertEquals("interrupted", e.getMessage());
                                interrupted.set(true);
                            });
            thread.start();
            Thread.sleep(100);
            ni.requestInterrupt();
            SECONDS.timedJoin(thread, 10);
            assertTrue(interrupted.get());
        }
    }

    @Test
    public void shouldInterruptViaThreadInterrupt() throws InterruptedException {
        try (var ni = buildInstance("compiled/infinite-loop.c.wasm")) {
            var function = ni.instance().export("run");
            AtomicBoolean interrupted = new AtomicBoolean();
            Thread thread =
                    new Thread(
                            () -> {
                                Thread.currentThread().interrupt();
                                var e = assertThrows(ChicoryException.class, function::apply);
                                assertEquals("interrupted", e.getMessage());
                                interrupted.set(true);
                            });
            thread.start();
            SECONDS.timedJoin(thread, 10);
            assertTrue(interrupted.get());
        }
    }

    private static RedlineInstance buildInstance(String resource) {
        var module = Parser.parse(CorpusResources.getResource(resource));
        return JffiNativeMachineFactory.builder(module)
                .withCompilerFunction(
                        m -> new NativeCompiler(RedlineTarget.detectHost(), m).compileAll())
                .build();
    }
}
