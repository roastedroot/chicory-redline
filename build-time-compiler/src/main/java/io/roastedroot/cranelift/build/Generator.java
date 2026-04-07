package io.roastedroot.cranelift.build;

import static com.dylibso.chicory.wasm.Encoding.readVarUInt32;
import static com.dylibso.chicory.wasm.WasmWriter.writeVarUInt32;
import static com.github.javaparser.StaticJavaParser.parseClassOrInterfaceType;
import static com.github.javaparser.StaticJavaParser.parseType;
import static com.github.javaparser.ast.NodeList.nodeList;

import com.dylibso.chicory.codegen.CodegenUtils;
import com.dylibso.chicory.codegen.ModuleInterfaceCodegen;
import com.dylibso.chicory.compiler.InterpreterFallback;
import com.dylibso.chicory.compiler.internal.ByteClassCollector;
import com.dylibso.chicory.compiler.internal.Compiler;
import com.dylibso.chicory.wasm.Parser;
import com.dylibso.chicory.wasm.WasmModule;
import com.dylibso.chicory.wasm.WasmWriter;
import com.dylibso.chicory.wasm.types.OpCode;
import com.dylibso.chicory.wasm.types.RawSection;
import com.dylibso.chicory.wasm.types.SectionId;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.ClassExpr;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.NullLiteralExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.expr.VariableDeclarationExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.CatchClause;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.ReturnStmt;
import com.github.javaparser.ast.stmt.ThrowStmt;
import com.github.javaparser.ast.stmt.TryStmt;
import io.roastedroot.cranelift.compiler.CraneliftTarget;
import io.roastedroot.cranelift.compiler.NativeCodeSerializer;
import io.roastedroot.cranelift.compiler.internal.NativeCompiler;
import java.io.ByteArrayOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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

    public void generateChicoryBytecode() throws IOException {
        var module = Parser.parse(config.wasmFile());
        var machineName = config.className() + "Machine";
        var compiler =
                Compiler.builder(module)
                        .withClassName(machineName)
                        .withClassCollectorFactory(ByteClassCollector::new)
                        .withInterpreterFallback(InterpreterFallback.WARN)
                        .build();
        var result = compiler.compile();

        var classFolder = config.targetClassFolder();
        for (Map.Entry<String, byte[]> entry : result.classBytes().entrySet()) {
            var binaryName = entry.getKey().replace('.', '/') + ".class";
            var targetFile = classFolder.resolve(binaryName);
            Files.createDirectories(targetFile.getParent());
            Files.write(targetFile, entry.getValue());
        }
    }

    public void generateModuleInterface() throws IOException {
        var moduleInterfaceName = config.moduleInterface();
        var module = Parser.parse(config.wasmFile());

        var lastDot = moduleInterfaceName.lastIndexOf('.');
        var packageName = (lastDot > 0) ? moduleInterfaceName.substring(0, lastDot) : "";
        var typeName = moduleInterfaceName.substring(lastDot + 1);

        var codegen =
                ModuleInterfaceCodegen.builder(module)
                        .withPackageName(packageName)
                        .withTypeName(typeName)
                        .withGeneratorName("io.roastedroot.cranelift.build.Generator")
                        .build();
        var classes = codegen.generate();

        for (var entry : classes.entrySet()) {
            var filePath =
                    config.targetSourceFolder().resolve(entry.getKey().replace('.', '/') + ".java");
            Files.createDirectories(filePath.getParent());
            Files.writeString(
                    filePath, entry.getValue().printer(CodegenUtils.printer()).toString());
        }
    }

    @SuppressWarnings("StringSplitter")
    public void generateSources() throws IOException {
        var split = config.className().split("\\.");
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

    private String generateSourceCode(String packageName, String baseName) {
        var cu = new CompilationUnit(packageName);
        cu.addImport(Parser.class);
        cu.addImport(WasmModule.class);
        cu.addImport(CraneliftTarget.class);
        cu.addImport(NativeCodeSerializer.class);
        cu.addImport("io.roastedroot.cranelift.compiler.CraneliftInstance");
        cu.addImport(IOException.class);
        cu.addImport(InputStream.class);
        cu.addImport(UncheckedIOException.class);

        var mainClass = cu.addClass(baseName, Modifier.Keyword.PUBLIC, Modifier.Keyword.FINAL);
        mainClass.addConstructor(Modifier.Keyword.PRIVATE);

        generateWasmModuleHolderInnerClass(mainClass, baseName);
        generateNativeCodeHolderInnerClass(mainClass, baseName, config.bytecodeFallback());
        generateLoadMethod(mainClass);
        generateLoadNativeCodeMethod(mainClass);
        if (config.bytecodeFallback()) {
            generateCreateMethod(cu, mainClass, baseName);
            generateUniversalBuilderMethod(cu, mainClass);
        } else {
            generateBuilderMethod(mainClass);
        }

        return cu.toString();
    }

    private static void generateWasmModuleHolderInnerClass(
            ClassOrInterfaceDeclaration type, String baseName) {
        var holderClass =
                new ClassOrInterfaceDeclaration(
                        nodeList(
                                new Modifier(Modifier.Keyword.PRIVATE),
                                new Modifier(Modifier.Keyword.STATIC)),
                        false,
                        "WasmModuleHolder");
        type.addMember(holderClass);

        holderClass.addField(
                WasmModule.class, "INSTANCE", Modifier.Keyword.STATIC, Modifier.Keyword.FINAL);

        // try (InputStream in = X.class.getResourceAsStream("X.meta")) {
        //     INSTANCE = Parser.parse(in);
        // } catch (IOException e) {
        //     throw new UncheckedIOException("Failed to load WASM module", e);
        // }
        var getResource =
                new MethodCallExpr(
                        new ClassExpr(parseType(baseName)),
                        "getResourceAsStream",
                        nodeList(new StringLiteralExpr(baseName + ".meta")));
        var resourceVar =
                new VariableDeclarationExpr(
                        new com.github.javaparser.ast.body.VariableDeclarator(
                                parseType("InputStream"), "in", getResource));

        var assignStmt =
                new ExpressionStmt(
                        new AssignExpr(
                                new NameExpr("INSTANCE"),
                                new MethodCallExpr(
                                        new NameExpr("Parser"),
                                        "parse",
                                        nodeList(new NameExpr("in"))),
                                AssignExpr.Operator.ASSIGN));

        var catchClause = ioCatchClause("Failed to load WASM module");

        var tryStmt =
                new TryStmt()
                        .setResources(nodeList(resourceVar))
                        .setTryBlock(new BlockStmt(nodeList(assignStmt)))
                        .setCatchClauses(nodeList(catchClause));

        holderClass.addStaticInitializer().addStatement(tryStmt);
    }

    private static void generateNativeCodeHolderInnerClass(
            ClassOrInterfaceDeclaration type, String baseName, boolean graceful) {
        var holderClass =
                new ClassOrInterfaceDeclaration(
                        nodeList(
                                new Modifier(Modifier.Keyword.PRIVATE),
                                new Modifier(Modifier.Keyword.STATIC)),
                        false,
                        "NativeCodeHolder");
        type.addMember(holderClass);

        holderClass.addField(
                parseType("byte[][]"), "CODE", Modifier.Keyword.STATIC, Modifier.Keyword.FINAL);

        // String host = CraneliftTarget.detectHost();
        var detectHost = new MethodCallExpr(new NameExpr("CraneliftTarget"), "detectHost");
        var hostDecl =
                new VariableDeclarationExpr(
                        new com.github.javaparser.ast.body.VariableDeclarator(
                                parseType("String"), "host", detectHost));

        // String suffix = CraneliftTarget.resourceSuffix(host);
        var suffixInit =
                new MethodCallExpr(
                        new NameExpr("CraneliftTarget"),
                        "resourceSuffix",
                        nodeList(new NameExpr("host")));
        var suffixDecl =
                new VariableDeclarationExpr(
                        new com.github.javaparser.ast.body.VariableDeclarator(
                                parseType("String"), "suffix", suffixInit));

        // String resource = "X." + suffix + ".native";
        var resourceConcat =
                new BinaryExpr(
                        new BinaryExpr(
                                new StringLiteralExpr(baseName + "."),
                                new NameExpr("suffix"),
                                BinaryExpr.Operator.PLUS),
                        new StringLiteralExpr(".native"),
                        BinaryExpr.Operator.PLUS);
        var resourceDecl =
                new VariableDeclarationExpr(
                        new com.github.javaparser.ast.body.VariableDeclarator(
                                parseType("String"), "resource", resourceConcat));

        // try (InputStream in = X.class.getResourceAsStream(resource)) { ... }
        var getResource =
                new MethodCallExpr(
                        new ClassExpr(parseType(baseName)),
                        "getResourceAsStream",
                        nodeList(new NameExpr("resource")));
        var resourceVar =
                new VariableDeclarationExpr(
                        new com.github.javaparser.ast.body.VariableDeclarator(
                                parseType("InputStream"), "in", getResource));

        // if (in == null) ...
        var nullCheck =
                new BinaryExpr(
                        new NameExpr("in"), new NullLiteralExpr(), BinaryExpr.Operator.EQUALS);

        // CODE = NativeCodeSerializer.deserialize(in);
        var deserializeAssign =
                new ExpressionStmt(
                        new AssignExpr(
                                new NameExpr("CODE"),
                                new MethodCallExpr(
                                        new NameExpr("NativeCodeSerializer"),
                                        "deserialize",
                                        nodeList(new NameExpr("in"))),
                                AssignExpr.Operator.ASSIGN));

        BlockStmt tryBlock;
        if (graceful) {
            // if (in == null) { CODE = null; } else { CODE = deserialize(in); }
            var assignNull =
                    new ExpressionStmt(
                            new AssignExpr(
                                    new NameExpr("CODE"),
                                    new NullLiteralExpr(),
                                    AssignExpr.Operator.ASSIGN));
            var ifElse =
                    new IfStmt(
                            nullCheck,
                            new BlockStmt(nodeList(assignNull)),
                            new BlockStmt(nodeList(deserializeAssign)));
            tryBlock = new BlockStmt(nodeList(ifElse));
        } else {
            // if (in == null) throw ...; CODE = deserialize(in);
            var unsupportedEx =
                    new ObjectCreationExpr()
                            .setType(parseClassOrInterfaceType("UnsupportedOperationException"))
                            .addArgument(
                                    new BinaryExpr(
                                            new StringLiteralExpr(
                                                    "No precompiled native code for platform: "),
                                            new NameExpr("suffix"),
                                            BinaryExpr.Operator.PLUS));
            var ifNull = new IfStmt(nullCheck, new ThrowStmt(unsupportedEx), null);
            tryBlock = new BlockStmt(nodeList(ifNull, deserializeAssign));
        }
        // throw new UncheckedIOException("Failed to load native code for " + host, e)
        var nativeErrorMsg =
                new BinaryExpr(
                        new StringLiteralExpr("Failed to load native code for platform: "),
                        new NameExpr("host"),
                        BinaryExpr.Operator.PLUS);
        var nativeErrorExpr =
                new ObjectCreationExpr()
                        .setType(parseClassOrInterfaceType("UncheckedIOException"))
                        .addArgument(nativeErrorMsg)
                        .addArgument(new NameExpr("e"));
        var catchClause =
                new CatchClause()
                        .setParameter(new Parameter(parseClassOrInterfaceType("IOException"), "e"))
                        .setBody(new BlockStmt(nodeList(new ThrowStmt(nativeErrorExpr))));

        var tryStmt =
                new TryStmt()
                        .setResources(nodeList(resourceVar))
                        .setTryBlock(tryBlock)
                        .setCatchClauses(nodeList(catchClause));

        var initBody = holderClass.addStaticInitializer();
        initBody.addStatement(new ExpressionStmt(hostDecl));
        if (graceful) {
            // if (host == null) { CODE = null; } else { ... load code ... }
            var hostNullCheck =
                    new BinaryExpr(
                            new NameExpr("host"),
                            new NullLiteralExpr(),
                            BinaryExpr.Operator.EQUALS);
            var hostNullAssign =
                    new ExpressionStmt(
                            new AssignExpr(
                                    new NameExpr("CODE"),
                                    new NullLiteralExpr(),
                                    AssignExpr.Operator.ASSIGN));
            var elseBlock = new BlockStmt();
            elseBlock.addStatement(new ExpressionStmt(suffixDecl));
            elseBlock.addStatement(new ExpressionStmt(resourceDecl));
            elseBlock.addStatement(tryStmt);
            initBody.addStatement(
                    new IfStmt(hostNullCheck, new BlockStmt(nodeList(hostNullAssign)), elseBlock));
        } else {
            initBody.addStatement(new ExpressionStmt(suffixDecl));
            initBody.addStatement(new ExpressionStmt(resourceDecl));
            initBody.addStatement(tryStmt);
        }
    }

    private static void generateLoadMethod(ClassOrInterfaceDeclaration type) {
        var method =
                type.addMethod("load", Modifier.Keyword.PUBLIC, Modifier.Keyword.STATIC)
                        .setType(WasmModule.class);
        method.setJavadocComment(
                "Returns the parsed Wasm module. For advanced use only;\n"
                        + "prefer {@link #builder()} for automatic backend selection.");
        method.createBody()
                .addStatement(
                        new ReturnStmt(
                                new FieldAccessExpr(new NameExpr("WasmModuleHolder"), "INSTANCE")));
    }

    private static void generateLoadNativeCodeMethod(ClassOrInterfaceDeclaration type) {
        var method =
                type.addMethod("loadNativeCode", Modifier.Keyword.PUBLIC, Modifier.Keyword.STATIC)
                        .setType(parseType("byte[][]"));
        method.setJavadocComment(
                "Returns the precompiled native code for the current platform,\n"
                        + "or {@code null} if no native code is available. For advanced use only;\n"
                        + "prefer {@link #builder()} for automatic backend selection.");
        method.createBody()
                .addStatement(
                        new ReturnStmt(
                                new FieldAccessExpr(new NameExpr("NativeCodeHolder"), "CODE")));
    }

    private static void generateCreateMethod(
            CompilationUnit cu, ClassOrInterfaceDeclaration type, String baseName) {
        cu.addImport("com.dylibso.chicory.runtime.Instance");
        cu.addImport("com.dylibso.chicory.runtime.Machine");

        // public static Machine create(Instance instance) {
        //     return new {BaseName}Machine(instance);
        // }
        var machineName = baseName + "Machine";
        var constructorCall =
                new ObjectCreationExpr()
                        .setType(parseClassOrInterfaceType(machineName))
                        .addArgument(new NameExpr("instance"));

        var method =
                type.addMethod("create", Modifier.Keyword.PUBLIC, Modifier.Keyword.STATIC)
                        .setType(parseClassOrInterfaceType("Machine"))
                        .addParameter(parseClassOrInterfaceType("Instance"), "instance");
        method.setJavadocComment(
                "Creates a Chicory JVM bytecode Machine for Chicory-compatible usage.\n"
                        + "For advanced use only; prefer {@link #builder()} for automatic\n"
                        + "backend selection.");
        method.createBody().addStatement(new ReturnStmt(constructorCall));
    }

    private static void generateUniversalBuilderMethod(
            CompilationUnit cu, ClassOrInterfaceDeclaration type) {
        cu.addImport("io.roastedroot.cranelift4j.UniversalInstance");

        // return UniversalInstance.builder(WasmModuleHolder.INSTANCE)
        //     .withPrecompiledCode(NativeCodeHolder.CODE)
        //     .withChicoryFallback(baseName::create);
        var builderCall =
                new MethodCallExpr(
                        new MethodCallExpr(
                                new MethodCallExpr(
                                        new NameExpr("UniversalInstance"),
                                        "builder",
                                        nodeList(
                                                new FieldAccessExpr(
                                                        new NameExpr("WasmModuleHolder"),
                                                        "INSTANCE"))),
                                "withPrecompiledCode",
                                nodeList(
                                        new FieldAccessExpr(
                                                new NameExpr("NativeCodeHolder"), "CODE"))),
                        "withChicoryFallback",
                        nodeList(
                                new com.github.javaparser.ast.expr.MethodReferenceExpr()
                                        .setScope(new NameExpr(type.getNameAsString()))
                                        .setIdentifier("create")));

        var method =
                type.addMethod("builder", Modifier.Keyword.PUBLIC, Modifier.Keyword.STATIC)
                        .setType(parseClassOrInterfaceType("CraneliftInstance.Builder"));
        method.setJavadocComment(
                "Creates a builder that automatically selects the best available backend:\n"
                        + "Cranelift native code when a runner is on the classpath, or\n"
                        + "Chicory JVM bytecode otherwise. Use {@code .build()} to obtain\n"
                        + "a {@link CraneliftInstance} and call\n"
                        + "{@link CraneliftInstance#isNative()} to check which backend\n"
                        + "was selected.");
        method.createBody().addStatement(new ReturnStmt(builderCall));
    }

    private static void generateBuilderMethod(ClassOrInterfaceDeclaration type) {
        var factoryCall =
                new MethodCallExpr(
                        new MethodCallExpr(
                                new NameExpr("CraneliftInstance"),
                                "builder",
                                nodeList(
                                        new FieldAccessExpr(
                                                new NameExpr("WasmModuleHolder"), "INSTANCE"))),
                        "withPrecompiledCode",
                        nodeList(new FieldAccessExpr(new NameExpr("NativeCodeHolder"), "CODE")));

        var method =
                type.addMethod("builder", Modifier.Keyword.PUBLIC, Modifier.Keyword.STATIC)
                        .setType(parseClassOrInterfaceType("CraneliftInstance.Builder"));
        method.setJavadocComment(
                "Creates a builder backed by Cranelift native code.\n"
                        + "Requires a native runner (Panama or jffi) on the classpath.");
        method.createBody().addStatement(new ReturnStmt(factoryCall));
    }

    private static CatchClause ioCatchClause(String message) {
        var newException =
                new ObjectCreationExpr()
                        .setType(parseClassOrInterfaceType("UncheckedIOException"))
                        .addArgument(new StringLiteralExpr(message))
                        .addArgument(new NameExpr("e"));
        return new CatchClause()
                .setParameter(new Parameter(parseClassOrInterfaceType("IOException"), "e"))
                .setBody(new BlockStmt(nodeList(new ThrowStmt(newException))));
    }
}
