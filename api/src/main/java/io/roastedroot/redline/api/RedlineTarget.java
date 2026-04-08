package io.roastedroot.redline.api;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public final class RedlineTarget {

    private RedlineTarget() {}

    public static final String LINUX_X86_64 = "x86_64-unknown-linux-gnu";
    public static final String LINUX_AARCH64 = "aarch64-unknown-linux-gnu";
    public static final String MACOS_X86_64 = "x86_64-apple-darwin";
    public static final String MACOS_AARCH64 = "aarch64-apple-darwin";
    public static final String WINDOWS_X86_64 = "x86_64-pc-windows-msvc";
    public static final String WINDOWS_AARCH64 = "aarch64-pc-windows-msvc";

    /** Default targets: all supported platforms, x86_64 and aarch64. */
    public static final List<String> ALL_TARGETS =
            Collections.unmodifiableList(
                    Arrays.asList(
                            LINUX_X86_64,
                            LINUX_AARCH64,
                            MACOS_X86_64,
                            MACOS_AARCH64,
                            WINDOWS_X86_64,
                            WINDOWS_AARCH64));

    /**
     * Returns a short resource suffix for a target triple.
     * e.g. "x86_64-unknown-linux-gnu" → "x86_64-linux"
     */
    public static String resourceSuffix(String triple) {
        String arch = triple.contains("aarch64") ? "aarch64" : "x86_64";
        String os;
        if (triple.contains("linux")) {
            os = "linux";
        } else if (triple.contains("darwin") || triple.contains("apple")) {
            os = "darwin";
        } else if (triple.contains("windows")) {
            os = "windows";
        } else {
            os = "unknown";
        }
        return arch + "-" + os;
    }

    /**
     * Detects the target triple for the current platform.
     */
    public static String detectHost() {
        String osName =
                System.getProperty("redline.os.name", System.getProperty("os.name", ""))
                        .toLowerCase(Locale.ROOT);
        String arch =
                System.getProperty("redline.os.arch", System.getProperty("os.arch", ""))
                        .toLowerCase(Locale.ROOT);

        boolean isAarch64 = arch.equals("aarch64") || arch.equals("arm64");

        if (osName.contains("linux")) {
            return isAarch64 ? LINUX_AARCH64 : LINUX_X86_64;
        } else if (osName.contains("mac") || osName.contains("darwin")) {
            return isAarch64 ? MACOS_AARCH64 : MACOS_X86_64;
        } else if (osName.contains("windows")) {
            return isAarch64 ? WINDOWS_AARCH64 : WINDOWS_X86_64;
        }
        // Unknown platform — no native code available
        return null;
    }
}
