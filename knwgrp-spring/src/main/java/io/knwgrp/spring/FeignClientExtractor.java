package io.knwgrp.spring;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.*;
import io.knwgrp.core.AnalysisContext;
import io.knwgrp.core.ConfigModel;
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
 * For every interface tagged {@code springStereotype=feignClient} (has {@code @FeignClient}),
 * extracts the target service name/url and resolves it via {@link ConfigModel} when it's a
 * {@code ${...}} placeholder, producing an EXTERNAL_SERVICE node and a DEPENDS_ON edge from the
 * client interface. This is one of the two extractors (with Kafka) that make downstream/
 * external dependencies visible in the graph without needing any runtime tracing.
 */
public class FeignClientExtractor implements Extractor {

    private static final Logger log = LoggerFactory.getLogger(FeignClientExtractor.class);

    @Override
    public String name() {
        return "FeignClientExtractor";
    }

    @Override
    public void extract(AnalysisContext context) {
        List<Node> feignClients = context.graph().getNodes().stream()
                .filter(n -> n.getType() == NodeType.CLASS || n.getType() == NodeType.INTERFACE)
                .filter(n -> "feignClient".equals(n.getAttribute("springStereotype")))
                .toList();

        for (Node client : feignClients) {
            Provenance prov = client.getProvenance();
            if (prov == null) continue;
            Path file = context.repoRoot().resolve(prov.filePath());
            if (!Files.exists(file)) continue;

            try {
                processFile(context, file, prov.filePath(), client);
            } catch (Exception e) {
                log.warn("Failed extracting Feign client from {}: {}", prov.filePath(), e.toString());
            }
        }
    }

    private void processFile(AnalysisContext context, Path file, String relativePath, Node clientNode) throws IOException {
        CompilationUnit cu = StaticJavaParser.parse(file);
        String simpleName = clientNode.getName();

        cu.getTypes().stream()
                .filter(t -> t.getNameAsString().equals(simpleName))
                .findFirst()
                .ifPresent(type -> processType(context, type, relativePath, clientNode));
    }

    private void processType(AnalysisContext context, TypeDeclaration<?> type, String relativePath, Node clientNode) {
        for (AnnotationExpr ann : type.getAnnotations()) {
            if (!ann.getNameAsString().equals("FeignClient")) continue;

            String rawName = memberValue(ann, "name").or(() -> memberValue(ann, "value")).orElse(null);
            String rawUrl = memberValue(ann, "url").orElse(null);

            String serviceName = rawName != null ? resolvePlaceholder(context, rawName) : null;
            String serviceUrl = rawUrl != null ? resolvePlaceholder(context, rawUrl) : null;

            String targetIdentity = serviceUrl != null ? serviceUrl : (serviceName != null ? serviceName : clientNode.getName());

            Node externalService = new Node(NodeIds.forExternalService(targetIdentity), NodeType.EXTERNAL_SERVICE, targetIdentity)
                    .withAttribute("declaredName", rawName)
                    .withAttribute("declaredUrl", rawUrl)
                    .withAttribute("resolvedUrl", serviceUrl)
                    .withProvenance(Provenance.of(relativePath, type.getBegin().map(p -> p.line).orElse(-1), name()));
            context.graph().addNode(externalService);

            String classFqn = String.valueOf(clientNode.getAttribute("fqn"));
            context.graph().addEdge(new Edge(NodeIds.forClass(classFqn), externalService.getId(), EdgeType.DEPENDS_ON)
                    .withAttribute("via", "FeignClient")
                    .withProvenance(Provenance.of(relativePath, type.getBegin().map(p -> p.line).orElse(-1), name())));
        }
    }

    private String resolvePlaceholder(AnalysisContext context, String raw) {
        if (!raw.contains("${")) {
            return raw;
        }
        return context.config().resolve(raw).orElse(raw + " (unresolved)");
    }

    private Optional<String> memberValue(AnnotationExpr ann, String memberName) {
        if (ann instanceof SingleMemberAnnotationExpr single && (memberName.equals("value") || memberName.equals("name"))) {
            return stringValue(single.getMemberValue());
        }
        if (ann instanceof NormalAnnotationExpr normal) {
            for (MemberValuePair pair : normal.getPairs()) {
                if (pair.getNameAsString().equals(memberName)) {
                    return stringValue(pair.getValue());
                }
            }
        }
        return Optional.empty();
    }

    private Optional<String> stringValue(Expression expr) {
        if (expr instanceof StringLiteralExpr str) {
            return Optional.of(str.asString());
        }
        return Optional.empty();
    }
}
