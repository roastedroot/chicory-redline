package io.roastedroot.cranelift.maven;

import io.roastedroot.cranelift.build.Config;
import io.roastedroot.cranelift.build.Generator;
import java.io.File;
import java.io.IOException;
import java.util.List;
import org.apache.maven.model.Resource;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;

/**
 * Compiles a Wasm module to native code at build time via Cranelift.
 * By default compiles for all supported targets (cross-compilation).
 */
@Mojo(name = "compile", defaultPhase = LifecyclePhase.GENERATE_SOURCES, threadSafe = true)
public class CraneliftCompilerMojo extends AbstractMojo {

    @Parameter(required = true)
    private File wasmFile;

    /** Fully qualified name of the generated class (e.g. "com.example.MyModule"). */
    @Parameter(required = true)
    private String name;

    @Parameter(
            required = true,
            defaultValue = "${project.build.directory}/generated-resources/cranelift-compiler")
    private File targetResourceFolder;

    @Parameter(
            required = true,
            defaultValue = "${project.build.directory}/generated-sources/cranelift-compiler")
    private File targetSourceFolder;

    /** Target triples to compile for. Defaults to all supported targets. */
    @Parameter private List<String> targets;

    /** Generate JVM bytecode fallback for unsupported platforms. */
    @Parameter(defaultValue = "true")
    private boolean bytecodeFallback;

    /**
     * Fully qualified name for typed module interface (e.g. "com.example.MyModule").
     * When set, generates _ModuleExports and _ModuleImports classes.
     */
    @Parameter private String moduleInterface;

    @Parameter(
            required = true,
            defaultValue = "${project.build.directory}/generated-resources/cranelift-compiler")
    private File targetClassFolder;

    @Parameter(property = "project", required = true, readonly = true)
    private MavenProject project;

    @Override
    public void execute() throws MojoExecutionException {
        getLog().info("Cranelift: compiling " + name + " from " + wasmFile);

        var configBuilder =
                Config.builder()
                        .withWasmFile(wasmFile.toPath())
                        .withName(name)
                        .withTargetResourceFolder(targetResourceFolder.toPath())
                        .withTargetSourceFolder(targetSourceFolder.toPath())
                        .withBytecodeFallback(bytecodeFallback)
                        .withTargetClassFolder(targetClassFolder.toPath());
        if (targets != null && !targets.isEmpty()) {
            configBuilder.withTargets(targets);
        }
        if (moduleInterface != null && !moduleInterface.isEmpty()) {
            configBuilder.withModuleInterface(moduleInterface);
        }
        var config = configBuilder.build();

        var generator = new Generator(config);

        try {
            generator.generateNativeCode();
            generator.generateMetaWasm();
            if (bytecodeFallback) {
                generator.generateChicoryBytecode();
            }
            if (config.moduleInterface() != null) {
                generator.generateModuleInterface();
            }
            generator.generateSources();
        } catch (IOException e) {
            throw new MojoExecutionException("Failed to compile native code", e);
        }

        Resource resource = new Resource();
        resource.setDirectory(targetResourceFolder.getPath());
        project.addResource(resource);
        project.addCompileSourceRoot(targetSourceFolder.getPath());
    }
}
