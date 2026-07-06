package io.knwgrp.spring;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.TypeDeclaration;
import io.knwgrp.core.AnalysisContext;
import io.knwgrp.core.Extractor;
import io.knwgrp.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Builds the runtime wiring graph: for every Spring-managed class (controller/service/
 * repository/component/configuration/feignClient), records what it depends on via constructor
 * injection (the recommended and by far most common style in modern Spring) or field injection
 * ({@code @Autowired} fields). Produces INJECTS edges from the depending class to the
 * dependency's declared type.
 *
 * <p>Resolution is name-based (declared type -> same-package or already-qualified class),
 * consistent with the rest of the AST layer — this deliberately does not attempt to resolve
 * {@code @Qualifier}-disambiguated or interface-to-multiple-impls wiring, which requires a full
 * Spring context simulation; it records the declared type and leaves multi-impl ambiguity
 * visible in the graph rather than guessing.
 */
public class DependencyInjectionExtractor implements Extractor {

    private static final Logger log = LoggerFactory.getLogger(DependencyInjectionExtractor.class);

    private static final List<String> MANAGED_STEREOTYPES = List.of(
            "controller", "service", "repository", "component", "configuration", "feignClient"
    );

    @Override
    public String name() {
        return "DependencyInjectionExtractor";
    }

    @Override
    public void extract(AnalysisContext context) {
        List<Node> managedClasses = context.graph().getNodes().stream()
                .filter(n -> n.getType() == NodeType.CLASS || n.getType() == NodeType.INTERFACE)
                .filter(n -> MANAGED_STEREOTYPES.contains(String.valueOf(n.getAttribute("springStereotype"))))
                .toList();

        for (Node classNode : managedClasses) {
            Provenance prov = classNode.getProvenance();
            if (prov == null) continue;
            Path file = context.repoRoot().resolve(prov.filePath());
            if (!Files.exists(file)) continue;

            try {
                processFile(context, file, prov.filePath(), classNode);
            } catch (Exception e) {
                log.warn("Failed extracting DI graph from {}: {}", prov.filePath(), e.toString());
            }
        }
    }

    private void processFile(AnalysisContext context, Path file, String relativePath, Node classNode) throws IOException {
        CompilationUnit cu = StaticJavaParser.parse(file);
        String simpleName = classNode.getName();

        cu.getTypes().stream()
                .filter(t -> t.getNameAsString().equals(simpleName))
                .findFirst()
                .ifPresent(type -> processType(context, type, relativePath, classNode));
    }

    private void processType(AnalysisContext context, TypeDeclaration<?> type, String relativePath, Node classNode) {
        String packageName = String.valueOf(classNode.getAttribute("package"));
        String classFqn = String.valueOf(classNode.getAttribute("fqn"));

        List<ConstructorDeclaration> constructors = type.getConstructors();
        if (!constructors.isEmpty()) {
            // Prefer the constructor with the most parameters (the "real" injection constructor
            // when Lombok/manual overloads exist); Spring itself would pick @Autowired-annotated
            // or the sole constructor, but this heuristic is a reasonable static approximation.
            ConstructorDeclaration primary = constructors.stream()
                    .max((a, b) -> Integer.compare(a.getParameters().size(), b.getParameters().size()))
                    .orElseThrow();

            for (Parameter param : primary.getParameters()) {
                recordInjection(context, classFqn, param.getTypeAsString(), packageName, relativePath,
                        primary.getBegin().map(p -> p.line).orElse(-1));
            }
        }

        for (FieldDeclaration field : type.getFields()) {
            boolean isAutowired = field.getAnnotations().stream()
                    .anyMatch(a -> a.getNameAsString().equals("Autowired"));
            if (isAutowired) {
                field.getVariables().forEach(v ->
                        recordInjection(context, classFqn, v.getTypeAsString(), packageName, relativePath,
                                field.getBegin().map(p -> p.line).orElse(-1)));
            }
        }
    }

    private void recordInjection(AnalysisContext context, String classFqn, String declaredType, String packageName, String relativePath, int line) {
        String cleanType = stripGenerics(declaredType);
        if (isJdkOrPrimitiveType(cleanType)) {
            return;
        }
        String dependencyFqn = resolveTypeName(cleanType, packageName, context);

        context.graph().addEdge(new Edge(NodeIds.forClass(classFqn), NodeIds.forClass(dependencyFqn), EdgeType.INJECTS)
                .withProvenance(Provenance.of(relativePath, line, name())));
    }

    private String stripGenerics(String type) {
        int idx = type.indexOf('<');
        return idx == -1 ? type : type.substring(0, idx);
    }

    private boolean isJdkOrPrimitiveType(String type) {
        return type.matches("^(int|long|double|float|boolean|char|byte|short|void|String|Integer|Long|Double|Float|Boolean|Character|Byte|Short|Object|List|Map|Set|Optional)$");
    }

    /** If a class with this simple name already exists in the graph (any package), prefer that match; else assume same package. */
    private String resolveTypeName(String simpleName, String packageName, AnalysisContext context) {
        if (simpleName.contains(".")) {
            return simpleName;
        }
        String samePackageGuess = packageName.isEmpty() ? simpleName : packageName + "." + simpleName;
        if (context.graph().hasNode(NodeIds.forClass(samePackageGuess))) {
            return samePackageGuess;
        }
        return context.graph().getNodes().stream()
                .filter(n -> n.getType() == NodeType.CLASS || n.getType() == NodeType.INTERFACE)
                .filter(n -> simpleName.equals(n.getName()))
                .map(n -> String.valueOf(n.getAttribute("fqn")))
                .findFirst()
                .orElse(samePackageGuess);
    }
}
