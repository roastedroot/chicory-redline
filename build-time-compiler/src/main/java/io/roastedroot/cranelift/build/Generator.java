package io.roastedroot.cranelift.build;

import static com.dylibso.chicory.wasm.Encoding.readVarUInt32;
import static com.dylibso.chicory.wasm.WasmWriter.writeVarUInt32;

import com.dylibso.chicory.wasm.Parser;
import com.dylibso.chicory.wasm.WasmWriter;
import com.dylibso.chicory.wasm.types.OpCode;
import com.dylibso.chicory.wasm.types.RawSection;
import com.dylibso.chicory.wasm.types.SectionId;
import io.roastedroot.cranelift.compiler.CraneliftTarget;
import io.roastedroot.cranelift.compiler.NativeCodeSerializer;
import io.roastedroot.cranelift.compiler.internal.NativeCompiler;
import java.io.ByteArrayOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public class Generator {

    private final Config config;

    public Generator(Config config) {
        this.config = config;
    }

    public void generateNativeCode() throws IOException {
        var module = Parser.parse(config.wasmFile());
        var packagePath = config.getPackageName().replace('.', '/');
        var baseName = config.getBaseName();
        var targets = config.targets();

        var resourceDir = config.targetResourceFolder().resolve(packagePath);
        Files.createDirectories(resourceDir);

        if (targets.size() == 1) {
            compileForTarget(targets.get(0), module, resourceDir, baseName);
            return;
        }

        ExecutorService executor = Executors.newFixedThreadPool(targets.size());
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (String triple : targets) {
                futures.add(
                        executor.submit(
                                () -> {
                                    try {
                                        compileForTarget(triple, module, resourceDir, baseName);
                                    } catch (IOException e) {
                                        throw new RuntimeException(e);
                                    }
                                    return null;
                                }));
            }
            for (Future<?> f : futures) {
                f.get();
            }
        } catch (ExecutionException e) {
            if (e.getCause() instanceof IOException) {
                throw (IOException) e.getCause();
            }
            throw new IOException("Parallel compilation failed", e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Compilation interrupted", e);
        } finally {
            executor.shutdownNow();
        }
    }

    private static void compileForTarget(
            String triple,
            com.dylibso.chicory.wasm.WasmModule module,
            java.nio.file.Path resourceDir,
            String baseName)
            throws IOException {
        var compiler = new NativeCompiler(triple, module);
        byte[][] compiledCode = compiler.compileAll();

        var suffix = CraneliftTarget.resourceSuffix(triple);
        var nativeFile = resourceDir.resolve(baseName + "." + suffix + ".native");

        try (var out = new FileOutputStream(nativeFile.toFile())) {
            NativeCodeSerializer.serialize(compiledCode, out);
        }
    }

    public void generateMetaWasm() throws IOException {
        byte[] wasmBytes = Files.readAllBytes(config.wasmFile());
        var module = Parser.builder().includeSectionId(SectionId.CODE).build().parse(wasmBytes);

        var writer = new WasmWriter();
        Parser.parseWithoutDecoding(
                wasmBytes,
                section -> {
                    if (section.sectionId() == SectionId.CODE) {
                        var source = ByteBuffer.wrap(((RawSection) section).contents());
                        var out = new ByteArrayOutputStream();
                        int count = module.codeSection().functionBodyCount();
                        writeVarUInt32(out, count);
                        var actual = readVarUInt32(source);
                        assert count == actual;
                        for (int i = 0; i < count; i++) {
                            // Move past original function body
                            var bodySize = (int) readVarUInt32(source);
                            source.position(source.position() + bodySize - 1);
                            var endOp = source.get();
                            assert endOp == OpCode.END.opcode();

                            // Write stripped body: unreachable + end
                            writeVarUInt32(out, 3); // body size
                            writeVarUInt32(out, 0); // locals count
                            out.write(OpCode.UNREACHABLE.opcode());
                            out.write(OpCode.END.opcode());
                        }
                        writer.writeSection(SectionId.CODE, out.toByteArray());
                    } else if (section.sectionId() != SectionId.CUSTOM) {
                        writer.writeSection((RawSection) section);
                    }
                });

        var packagePath = config.getPackageName().replace('.', '/');
        var metaFile =
                config.targetResourceFolder()
                        .resolve(packagePath)
                        .resolve(config.getBaseName() + ".meta");
        Files.createDirectories(metaFile.getParent());
        Files.write(metaFile, writer.bytes());
    }

    @SuppressWarnings("StringSplitter")
    public void generateSources() throws IOException {
        var split = config.name().split("\\.");
        var sourceFolder = config.targetSourceFolder();
        for (int i = 0; i < split.length - 1; i++) {
            sourceFolder = sourceFolder.resolve(split[i]);
        }
        Files.createDirectories(sourceFolder);

        var baseName = config.getBaseName();
        var packageName = config.getPackageName();
        var sourceFile = sourceFolder.resolve(baseName + ".java");

        var source = generateSourceCode(packageName, baseName);
        Files.writeString(sourceFile, source);
    }

    private static String generateSourceCode(String packageName, String baseName) {
        return "package "
                + packageName
                + ";\n"
                + "\n"
                + "import com.dylibso.chicory.runtime.Instance;\n"
                + "import com.dylibso.chicory.wasm.Parser;\n"
                + "import com.dylibso.chicory.wasm.WasmModule;\n"
                + "import io.roastedroot.cranelift.compiler.CraneliftTarget;\n"
                + "import io.roastedroot.cranelift.compiler.NativeCodeSerializer;\n"
                + "import io.roastedroot.cranelift.runner.NativeMachineFactory;\n"
                + "import java.io.IOException;\n"
                + "import java.io.InputStream;\n"
                + "import java.io.UncheckedIOException;\n"
                + "\n"
                + "public final class "
                + baseName
                + " {\n"
                + "\n"
                + "    private "
                + baseName
                + "() {}\n"
                + "\n"
                + "    private static class WasmModuleHolder {\n"
                + "\n"
                + "        static final WasmModule INSTANCE;\n"
                + "\n"
                + "        static {\n"
                + "            try (InputStream in =\n"
                + "                    "
                + baseName
                + ".class.getResourceAsStream(\""
                + baseName
                + ".meta\")) {\n"
                + "                INSTANCE = Parser.parse(in);\n"
                + "            } catch (IOException e) {\n"
                + "                throw new UncheckedIOException(\n"
                + "                        \"Failed to load WASM module\", e);\n"
                + "            }\n"
                + "        }\n"
                + "    }\n"
                + "\n"
                + "    private static class NativeCodeHolder {\n"
                + "\n"
                + "        static final byte[][] CODE;\n"
                + "\n"
                + "        static {\n"
                + "            String suffix = CraneliftTarget.resourceSuffix(\n"
                + "                    CraneliftTarget.detectHost());\n"
                + "            String resource = \""
                + baseName
                + ".\" + suffix + \".native\";\n"
                + "            try (InputStream in =\n"
                + "                    "
                + baseName
                + ".class.getResourceAsStream(resource)) {\n"
                + "                if (in == null) {\n"
                + "                    throw new UnsupportedOperationException(\n"
                + "                            \"No precompiled native code for platform: \"\n"
                + "                                    + suffix);\n"
                + "                }\n"
                + "                CODE = NativeCodeSerializer.deserialize(in);\n"
                + "            } catch (IOException e) {\n"
                + "                throw new UncheckedIOException(\n"
                + "                        \"Failed to load native code\", e);\n"
                + "            }\n"
                + "        }\n"
                + "    }\n"
                + "\n"
                + "    public static WasmModule load() {\n"
                + "        return WasmModuleHolder.INSTANCE;\n"
                + "    }\n"
                + "\n"
                + "    public static Instance.Builder builder() {\n"
                + "        return fast();\n"
                + "    }\n"
                + "\n"
                + "    public static Instance.Builder fast() {\n"
                + "        WasmModule module = WasmModuleHolder.INSTANCE;\n"
                + "        NativeMachineFactory factory =\n"
                + "                new NativeMachineFactory(module, NativeCodeHolder.CODE);\n"
                + "        return Instance.builder(module)\n"
                + "                .withMachineFactory(factory::compile)\n"
                + "                .withTableFactory(factory::createTable)\n"
                + "                .withGlobalFactory(factory::createGlobal)\n"
                + "                .withMemoryFactory(NativeMachineFactory::createMemory);\n"
                + "    }\n"
                + "}\n";
    }
}
