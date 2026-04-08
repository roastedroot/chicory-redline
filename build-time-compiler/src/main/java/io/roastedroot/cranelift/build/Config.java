package io.roastedroot.cranelift.build;

import io.roastedroot.cranelift.api.CraneliftTarget;
import java.nio.file.Path;
import java.util.List;
import java.util.StringJoiner;

public final class Config {

    private final Path wasmFile;
    private final String name;
    private final Path targetResourceFolder;
    private final Path targetSourceFolder;
    private final List<String> targets;
    private final boolean bytecodeFallback;
    private final String moduleInterface;
    private final Path targetClassFolder;

    private Config(
            Path wasmFile,
            String name,
            Path targetResourceFolder,
            Path targetSourceFolder,
            List<String> targets,
            boolean bytecodeFallback,
            String moduleInterface,
            Path targetClassFolder) {
        this.wasmFile = wasmFile;
        this.name = name;
        this.targetResourceFolder = targetResourceFolder;
        this.targetSourceFolder = targetSourceFolder;
        this.targets = targets;
        this.bytecodeFallback = bytecodeFallback;
        this.moduleInterface = moduleInterface;
        this.targetClassFolder = targetClassFolder;
    }

    public Path wasmFile() {
        return wasmFile;
    }

    /** Fully qualified name of the generated class (e.g. "com.example.MyModule"). */
    public String name() {
        return name;
    }

    public Path targetResourceFolder() {
        return targetResourceFolder;
    }

    public Path targetSourceFolder() {
        return targetSourceFolder;
    }

    public List<String> targets() {
        return targets;
    }

    public boolean bytecodeFallback() {
        return bytecodeFallback;
    }

    public String moduleInterface() {
        return moduleInterface;
    }

    public Path targetClassFolder() {
        return targetClassFolder;
    }

    @SuppressWarnings("StringSplitter")
    public String getPackageName() {
        var split = name.split("\\.");
        StringJoiner packageName = new StringJoiner(".");
        for (int i = 0; i < split.length - 1; i++) {
            packageName.add(split[i]);
        }
        return packageName.toString();
    }

    @SuppressWarnings("StringSplitter")
    public String getBaseName() {
        var split = name.split("\\.");
        return split[split.length - 1];
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private Path wasmFile;
        private String name;
        private Path targetResourceFolder;
        private Path targetSourceFolder;
        private List<String> targets;
        private boolean bytecodeFallback = true;
        private String moduleInterface;
        private Path targetClassFolder;

        private Builder() {}

        public Builder withWasmFile(Path wasmFile) {
            this.wasmFile = wasmFile;
            return this;
        }

        /** Fully qualified name of the generated class (e.g. "com.example.MyModule"). */
        public Builder withName(String name) {
            this.name = name;
            return this;
        }

        public Builder withTargetResourceFolder(Path targetResourceFolder) {
            this.targetResourceFolder = targetResourceFolder;
            return this;
        }

        public Builder withTargetSourceFolder(Path targetSourceFolder) {
            this.targetSourceFolder = targetSourceFolder;
            return this;
        }

        public Builder withTargets(List<String> targets) {
            this.targets = targets;
            return this;
        }

        public Builder withBytecodeFallback(boolean bytecodeFallback) {
            this.bytecodeFallback = bytecodeFallback;
            return this;
        }

        public Builder withModuleInterface(String moduleInterface) {
            this.moduleInterface = moduleInterface;
            return this;
        }

        public Builder withTargetClassFolder(Path targetClassFolder) {
            this.targetClassFolder = targetClassFolder;
            return this;
        }

        public Config build() {
            List<String> t =
                    (targets != null && !targets.isEmpty()) ? targets : CraneliftTarget.ALL_TARGETS;
            return new Config(
                    wasmFile,
                    name,
                    targetResourceFolder,
                    targetSourceFolder,
                    t,
                    bytecodeFallback,
                    moduleInterface,
                    targetClassFolder);
        }
    }
}
