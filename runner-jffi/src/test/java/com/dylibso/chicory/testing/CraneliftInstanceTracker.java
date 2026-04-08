package com.dylibso.chicory.testing;

import io.roastedroot.cranelift.api.CraneliftInstance;
import java.util.Deque;
import java.util.concurrent.ConcurrentLinkedDeque;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * JUnit5 extension that closes all {@link CraneliftInstance}s created during
 * a test class. Registered via {@code META-INF/services} auto-detection so
 * generated spec tests get proper native resource cleanup without modification.
 */
public final class CraneliftInstanceTracker implements AfterAllCallback {

    private static final Deque<CraneliftInstance> INSTANCES = new ConcurrentLinkedDeque<>();

    static void register(CraneliftInstance instance) {
        INSTANCES.add(instance);
    }

    @Override
    public void afterAll(ExtensionContext context) {
        CraneliftInstance ci;
        while ((ci = INSTANCES.poll()) != null) {
            ci.close();
        }
    }
}
