package com.dylibso.chicory.testing;

import io.roastedroot.redline.api.RedlineInstance;
import java.util.Deque;
import java.util.concurrent.ConcurrentLinkedDeque;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * JUnit5 extension that closes all {@link RedlineInstance}s created during
 * a test class. Registered via {@code META-INF/services} auto-detection so
 * generated spec tests get proper native resource cleanup without modification.
 */
public final class RedlineInstanceTracker implements AfterAllCallback {

    private static final Deque<RedlineInstance> INSTANCES = new ConcurrentLinkedDeque<>();

    static void register(RedlineInstance instance) {
        INSTANCES.add(instance);
    }

    @Override
    public void afterAll(ExtensionContext context) {
        RedlineInstance ci;
        while ((ci = INSTANCES.poll()) != null) {
            ci.close();
        }
    }
}
