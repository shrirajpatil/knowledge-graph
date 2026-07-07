package io.knwgrp.java;

import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JavaParserTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;
import io.knwgrp.core.AnalysisContext;
import io.knwgrp.core.Extractor;
import io.knwgrp.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Walks every {@code .java} source file under the repo and extracts packages, classes,
 * interfaces, enums, methods, and fields into the graph, along with their {@code extends}/
 * {@code implements} relationships and annotations. This is the foundational structural layer
 * that {@code knwgrp-spring} builds Spring-specific semantics on top of.
 *
 * <p>Uses JavaParser with a symbol solver (source + reflection type solvers) so that method
 * calls and field types can later be resolved to fully-qualified names where possible; falls
 * back to the raw (unresolved) name when resolution fails, since a partially-analyzable
 * codebase is far more useful than one that aborts on the first unresolvable reference.
 */
public class JavaSourceExtractor implements Extractor {

    private static final Logger log = LoggerFactory.getLogger(JavaSourceExtractor.class);

    @Override
    public String name() {
        return "JavaSourceExtractor";
    }

    @Override
    public void extract(AnalysisContext context) {
        List<Path> javaFiles = findJavaFiles(context.repoRoot());
        if (javaFiles.isEmpty()) {
            log.warn("No .java source files found under {}", context.repoRoot());
            return;
        }

        configureParser(context.repoRoot(), javaFiles);

        for (Path file : javaFiles) {
            try {
                processFile(context, file);
            } catch (Exception e) {
                log.warn("Failed parsing {}: {}", file, e.toString());
            }
        }
    }

    private List<Path> findJavaFiles(Path repoRoot) {
        try (Stream<Path> stream = Files.walk(repoRoot)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> !isGeneratedOrBuild(p))
                    .toList();
        } catch (IOException e) {
            log.warn("Failed walking repo for java files: {}", e.toString());
            return List.of();
        }
    }

    private boolean isGeneratedOrBuild(Path p) {
        String s = p.toString().replace('\\', '/');
        return s.contains("/target/") || s.contains("/build/") || s.contains("/generated-sources/") || s.contains("/generated/");
    }

    /** Sets up a symbol solver rooted at every source directory found, plus the JDK via reflection. */
    private void configureParser(Path repoRoot, List<Path> javaFiles) {
        CombinedTypeSolver typeSolver = new CombinedTypeSolver();
        typeSolver.add(new ReflectionTypeSolver());

        javaFiles.stream()
                .map(this::findSourceRoot)
                .flatMap(Optional::stream)
                .distinct()
                .forEach(root -> {
                    try {
                        typeSolver.add(new JavaParserTypeSolver(root));
                    } catch (Exception e) {
                        log.debug("Could not add source root {} to type solver: {}", root, e.toString());
                    }
                });

        JavaSymbolSolver symbolSolver = new JavaSymbolSolver(typeSolver);
        ParserConfiguration parserConfig = new ParserConfiguration().setSymbolResolver(symbolSolver);
        StaticJavaParser.setConfiguration(parserConfig);
    }

    /** Walks up from a .java file to find its source root (the dir above the top-level package, conventionally src/main/java). */
    private Optional<Path> findSourceRoot(Path javaFile) {
        Path dir = javaFile.getParent();
        while (dir != null) {
            if (dir.getFileName() != null && dir.getFileName().toString().equals("java")
                    && dir.getParent() != null && dir.getParent().getFileName().toString().equals("main")) {
                return Optional.of(dir);
            }
            dir = dir.getParent();
        }
        return Optional.empty();
    }

    private void processFile(AnalysisContext context, Path file) throws IOException {
        String relativePath = context.relativize(file);
        CompilationUnit cu = StaticJavaParser.parse(file);

        String packageName = cu.getPackageDeclaration()
                .map(pd -> pd.getNameAsString())
                .orElse("");

        if (!packageName.isEmpty()) {
            Node pkgNode = new Node(NodeIds.forPackage(packageName), NodeType.PACKAGE, packageName)
                    .withProvenance(Provenance.ofFile(relativePath, name()));
            context.graph().addNode(pkgNode);
        }

        for (TypeDeclaration<?> type : cu.getTypes()) {
            processType(context, type, packageName, relativePath);
        }
    }

    private void processType(AnalysisContext context, TypeDeclaration<?> type, String packageName, String relativePath) {
        String simpleName = type.getNameAsString();
        String fqn = packageName.isEmpty() ? simpleName : packageName + "." + simpleName;

        NodeType nodeType = classifyType(type);
        Node classNode = new Node(NodeIds.forClass(fqn), nodeType, simpleName)
                .withAttribute("fqn", fqn)
                .withAttribute("package", packageName)
                .withProvenance(Provenance.of(relativePath, type.getBegin().map(p -> p.line).orElse(-1), name()));

        type.getJavadoc().ifPresent(doc -> classNode.withAttribute("javadoc", doc.getDescription().toText()));

        List<AnnotationExpr> annotations = type.getAnnotations();
        classNode.withAttribute("annotations", annotations.stream().map(AnnotationExpr::getNameAsString).toList());

        context.graph().addNode(classNode);

        if (!packageName.isEmpty()) {
            context.graph().addEdge(new Edge(NodeIds.forPackage(packageName), NodeIds.forClass(fqn), EdgeType.CONTAINS)
                    .withProvenance(Provenance.ofFile(relativePath, name())));
        }

        if (type instanceof ClassOrInterfaceDeclaration cid) {
            for (var ext : cid.getExtendedTypes()) {
                context.graph().addEdge(new Edge(NodeIds.forClass(fqn), NodeIds.forClass(resolveTypeName(ext.getNameAsString(), packageName)), EdgeType.EXTENDS)
                        .withProvenance(Provenance.of(relativePath, ext.getBegin().map(p -> p.line).orElse(-1), name())));
            }
            for (var impl : cid.getImplementedTypes()) {
                context.graph().addEdge(new Edge(NodeIds.forClass(fqn), NodeIds.forClass(resolveTypeName(impl.getNameAsString(), packageName)), EdgeType.IMPLEMENTS)
                        .withProvenance(Provenance.of(relativePath, impl.getBegin().map(p -> p.line).orElse(-1), name())));
            }
        }

        for (FieldDeclaration field : type.getFields()) {
            processField(context, field, fqn, relativePath);
        }

        for (MethodDeclaration method : type.getMethods()) {
            processMethod(context, method, fqn, relativePath);
        }

        for (var constructor : type.getConstructors()) {
            processConstructor(context, constructor, fqn, relativePath);
        }

        for (var member : type.getMembers()) {
            if (member instanceof TypeDeclaration<?> nested) {
                processType(context, nested, packageName, relativePath);
            }
        }
    }

    private NodeType classifyType(TypeDeclaration<?> type) {
        if (type instanceof EnumDeclaration) return NodeType.ENUM;
        if (type instanceof ClassOrInterfaceDeclaration cid) {
            return cid.isInterface() ? NodeType.INTERFACE : NodeType.CLASS;
        }
        return NodeType.CLASS;
    }

    /** Best-effort: if the simple name isn't already qualified, assume same package (a common case). */
    private String resolveTypeName(String simpleName, String packageName) {
        if (simpleName.contains(".")) {
            return simpleName;
        }
        return packageName.isEmpty() ? simpleName : packageName + "." + simpleName;
    }

    private void processField(AnalysisContext context, FieldDeclaration field, String classFqn, String relativePath) {
        for (VariableDeclarator var : field.getVariables()) {
            String fieldName = var.getNameAsString();
            Node fieldNode = new Node(NodeIds.forField(classFqn, fieldName), NodeType.FIELD, fieldName)
                    .withAttribute("declaredType", var.getTypeAsString())
                    .withAttribute("annotations", field.getAnnotations().stream().map(AnnotationExpr::getNameAsString).toList())
                    .withProvenance(Provenance.of(relativePath, field.getBegin().map(p -> p.line).orElse(-1), name()));
            context.graph().addNode(fieldNode);
            context.graph().addEdge(new Edge(NodeIds.forClass(classFqn), fieldNode.getId(), EdgeType.CONTAINS)
                    .withProvenance(Provenance.ofFile(relativePath, name())));
        }
    }

    private void processMethod(AnalysisContext context, MethodDeclaration method, String classFqn, String relativePath) {
        String signature = buildSignature(method.getNameAsString(), method.getParameters());
        Node methodNode = new Node(NodeIds.forMethod(classFqn, signature), NodeType.METHOD, method.getNameAsString())
                .withAttribute("returnType", method.getTypeAsString())
                .withAttribute("parameters", method.getParameters().stream().map(p -> p.getTypeAsString() + " " + p.getNameAsString()).toList())
                .withAttribute("annotations", method.getAnnotations().stream().map(AnnotationExpr::getNameAsString).toList())
                .withAttribute("modifiers", method.getModifiers().stream().map(m -> m.getKeyword().asString()).toList())
                .withProvenance(Provenance.of(relativePath, method.getBegin().map(p -> p.line).orElse(-1), name()));

        method.getJavadoc().ifPresent(doc -> methodNode.withAttribute("javadoc", doc.getDescription().toText()));

        context.graph().addNode(methodNode);
        context.graph().addEdge(new Edge(NodeIds.forClass(classFqn), methodNode.getId(), EdgeType.CONTAINS)
                .withProvenance(Provenance.ofFile(relativePath, name())));
    }

    private void processConstructor(AnalysisContext context, ConstructorDeclaration constructor, String classFqn, String relativePath) {
        String signature = buildSignature("<init>", constructor.getParameters());
        Node ctorNode = new Node(NodeIds.forMethod(classFqn, signature), NodeType.METHOD, "<init>")
                .withAttribute("parameters", constructor.getParameters().stream().map(p -> p.getTypeAsString() + " " + p.getNameAsString()).toList())
                .withAttribute("isConstructor", true)
                .withProvenance(Provenance.of(relativePath, constructor.getBegin().map(p -> p.line).orElse(-1), name()));

        context.graph().addNode(ctorNode);
        context.graph().addEdge(new Edge(NodeIds.forClass(classFqn), ctorNode.getId(), EdgeType.CONTAINS)
                .withProvenance(Provenance.ofFile(relativePath, name())));
    }

    private String buildSignature(String methodName, List<Parameter> params) {
        StringBuilder sb = new StringBuilder(methodName).append("(");
        for (int i = 0; i < params.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append(params.get(i).getTypeAsString());
        }
        return sb.append(")").toString();
    }
}
