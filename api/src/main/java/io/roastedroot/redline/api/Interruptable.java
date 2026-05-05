package io.roastedroot.redline.api;

public interface Interruptable {
    void requestInterrupt();

    void clearInterrupt();
}
