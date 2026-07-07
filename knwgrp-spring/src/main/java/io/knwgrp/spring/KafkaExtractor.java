package io.knwgrp.spring;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.*;
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

/**
 * Detects Kafka messaging edges from two sources: {@code @KafkaListener(topics = "...")}
 * method annotations (consumer side) across every class in the graph, and
 * {@code kafkaTemplate.send("topic", ...)} call expressions (producer side, detected via a
 * simple method-call name match since full call-graph resolution isn't in scope yet).
 * Topic name literals or config placeholders are resolved via {@link io.knwgrp.core.ConfigModel}
 * the same way {@link FeignClientExtractor} resolves Feign URLs, keeping resolution behavior
 * consistent across extractors.
 */
public class KafkaExtractor implements Extractor {

    private static final Logger log = LoggerFactory.getLogger(KafkaExtractor.class);

    @Override
    public String name() {
        return "KafkaExtractor";
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
                log.warn("Failed extracting Kafka usage from {}: {}", prov.filePath(), e.toString());
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

        for (MethodDeclaration method : type.getMethods()) {
            for (AnnotationExpr ann : method.getAnnotations()) {
                if (!ann.getNameAsString().equals("KafkaListener")) continue;
                for (String topic : topicsFromAnnotation(ann)) {
                    String resolved = resolvePlaceholder(context, topic);
                    Node topicNode = topicNode(context, resolved, relativePath, method.getBegin().map(p -> p.line).orElse(-1));
                    String signature = buildSignature(method);
                    context.graph().addEdge(new Edge(topicNode.getId(), NodeIds.forMethod(classFqn, signature), EdgeType.LISTENS_TO)
                            .withProvenance(Provenance.of(relativePath, method.getBegin().map(p -> p.line).orElse(-1), name())));
                }
            }

            method.findAll(MethodCallExpr.class).stream()
                    .filter(call -> call.getNameAsString().equals("send") || call.getNameAsString().equals("sendDefault"))
                    .forEach(call -> {
                        List<Expression> args = call.getArguments();
                        if (args.isEmpty()) return;
                        Optional<String> topicLiteral = args.get(0) instanceof StringLiteralExpr str
                                ? Optional.of(str.asString())
                                : Optional.empty();
                        topicLiteral.ifPresent(topic -> {
                            String resolved = resolvePlaceholder(context, topic);
                            Node topicNode = topicNode(context, resolved, relativePath, call.getBegin().map(p -> p.line).orElse(-1));
                            String signature = buildSignature(method);
                            context.graph().addEdge(new Edge(NodeIds.forMethod(classFqn, signature), topicNode.getId(), EdgeType.PUBLISHES_TO)
                                    .withProvenance(Provenance.of(relativePath, call.getBegin().map(p -> p.line).orElse(-1), name())));
                        });
                    });
        }
    }

    private Node topicNode(AnalysisContext context, String topicName, String relativePath, int line) {
        Node node = new Node(NodeIds.forKafkaTopic(topicName), NodeType.KAFKA_TOPIC, topicName)
                .withProvenance(Provenance.of(relativePath, line, name()));
        return context.graph().addNode(node);
    }

    private String resolvePlaceholder(AnalysisContext context, String raw) {
        if (!raw.contains("${")) {
            return raw;
        }
        return context.config().resolve(raw).orElse(raw + " (unresolved)");
    }

    private List<String> topicsFromAnnotation(AnnotationExpr ann) {
        List<String> topics = new java.util.ArrayList<>();
        if (ann instanceof NormalAnnotationExpr normal) {
            for (MemberValuePair pair : normal.getPairs()) {
                if (pair.getNameAsString().equals("topics")) {
                    extractStrings(pair.getValue(), topics);
                }
            }
        } else if (ann instanceof SingleMemberAnnotationExpr single) {
            extractStrings(single.getMemberValue(), topics);
        }
        return topics;
    }

    private void extractStrings(Expression expr, List<String> out) {
        if (expr instanceof StringLiteralExpr str) {
            out.add(str.asString());
        } else if (expr instanceof ArrayInitializerExpr array) {
            array.getValues().forEach(e -> extractStrings(e, out));
        }
    }

    private String buildSignature(MethodDeclaration method) {
        StringBuilder sb = new StringBuilder(method.getNameAsString()).append("(");
        var params = method.getParameters();
        for (int i = 0; i < params.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append(params.get(i).getTypeAsString());
        }
        return sb.append(")").toString();
    }
}
