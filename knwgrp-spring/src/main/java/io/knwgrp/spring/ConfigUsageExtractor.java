package io.knwgrp.spring;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.SingleMemberAnnotationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import io.knwgrp.core.AnalysisContext;
import io.knwgrp.core.Extractor;
import io.knwgrp.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Links classes to the config keys they actually read via {@code @Value("${...}")} on fields or
 * constructor parameters, closing the loop between the config files {@code ConfigExtractor}
 * parses and the code that consumes them — without this, a CONFIG_PROPERTY node has no visible
 * "used by" edge even when it obviously is used, making config keys look orphaned in the graph.
 *
 * <p>Only links to a config key that {@code ConfigExtractor} actually found in a config file;
 * a {@code @Value("${typo.in.key}")} that matches nothing real is left unlinked rather than
 * creating a node for a key that was never actually declared anywhere.
 */
public class ConfigUsageExtractor implements Extractor {

    private static final Logger log = LoggerFactory.getLogger(ConfigUsageExtractor.class);
    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([^:}]+)(?::[^}]*)?}");

    @Override
    public String name() {
        return "ConfigUsageExtractor";
    }

    @Override
    public void extract(AnalysisContext context) {
        List<Node> classes = context.graph().getNodes().stream()
                .filter(n -> n.getType() == NodeType.CLASS || n.getType() == NodeType.INTERFACE)
                .toList();

        for (Node classNode : classes) {
            Provenance prov = classNode.getProvenance();
            if (prov == null) continue;
            Path file = context.repoRoot().resolve(prov.filePath());
            if (!Files.exists(file)) continue;

            try {
                processFile(context, file, prov.filePath(), classNode);
            } catch (Exception e) {
                log.warn("Failed extracting config usage from {}: {}", prov.filePath(), e.toString());
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
        String classFqn = String.valueOf(classNode.getAttribute("fqn"));

        for (FieldDeclaration field : type.getFields()) {
            for (AnnotationExpr ann : field.getAnnotations()) {
                if (!ann.getNameAsString().equals("Value")) continue;
                recordUsageIfPresent(context, classFqn, ann, relativePath, field.getBegin().map(p -> p.line).orElse(-1));
            }
        }
        for (ConstructorDeclaration ctor : type.getConstructors()) {
            for (Parameter param : ctor.getParameters()) {
                for (AnnotationExpr ann : param.getAnnotations()) {
                    if (!ann.getNameAsString().equals("Value")) continue;
                    recordUsageIfPresent(context, classFqn, ann, relativePath, ctor.getBegin().map(p -> p.line).orElse(-1));
                }
            }
        }
    }

    private void recordUsageIfPresent(AnalysisContext context, String classFqn, AnnotationExpr ann, String relativePath, int line) {
        if (!(ann instanceof SingleMemberAnnotationExpr single) || !(single.getMemberValue() instanceof StringLiteralExpr str)) {
            return;
        }
        Matcher matcher = PLACEHOLDER.matcher(str.asString());
        if (!matcher.find()) return;

        String configId = NodeIds.forConfigProperty(matcher.group(1));
        if (!context.graph().hasNode(configId)) return;

        context.graph().addEdge(new Edge(NodeIds.forClass(classFqn), configId, EdgeType.DEPENDS_ON)
                .withAttribute("via", "@Value")
                .withProvenance(Provenance.of(relativePath, line, name())));
    }
}
