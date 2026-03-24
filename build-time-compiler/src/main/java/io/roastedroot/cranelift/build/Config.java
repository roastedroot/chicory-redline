package io.roastedroot.cranelift.build;

import java.nio.file.Path;
import java.util.StringJoiner;

public final class Config {

    private final Path wasmFile;
    private final String name;
    private final Path targetResourceFolder;
    private final Path targetSourceFolder;

    private Config(Path wasmFile, String name, Path targetResourceFolder, Path targetSourceFolder) {
        this.wasmFile = wasmFile;
        this.name = name;
        this.targetResourceFolder = targetResourceFolder;
        this.targetSourceFolder = targetSourceFolder;
    }

    public Path wasmFile() {
        return wasmFile;
    }

    public String name() {
        return name;
    }

    public Path targetResourceFolder() {
        return targetResourceFolder;
    }

    public Path targetSourceFolder() {
        return targetSourceFolder;
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

        private Builder() {}

        public Builder withWasmFile(Path wasmFile) {
            this.wasmFile = wasmFile;
            return this;
        }

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

        public Config build() {
            return new Config(wasmFile, name, targetResourceFolder, targetSourceFolder);
        }
    }
}
