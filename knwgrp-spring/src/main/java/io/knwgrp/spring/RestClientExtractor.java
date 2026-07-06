package io.knwgrp.spring;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
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
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Detects outbound HTTP calls made through manual client patterns that predate (or coexist
 * with) Feign: {@code RestTemplate}, {@code WebClient}, and raw {@code OkHttpClient}. Many
 * Spring services — especially older or webflux-based ones — never use {@code @FeignClient}
 * at all, so without this extractor their entire downstream dependency graph would be
 * invisible (see {@link FeignClientExtractor} for the equivalent Feign-based detection).
 *
 * <p>Detection is call-site based rather than full data-flow analysis: for every method whose
 * declaring class has a field/constructor-param typed as one of the known client types, scan
 * for the client's characteristic call chains ({@code .exchange(}, {@code .getForObject(},
 * {@code .retrieve()}, {@code .newCall(...).execute()}) and pull the first String-literal
 * argument as a best-effort URL. When the URL is a config placeholder it is resolved the same
 * way {@link FeignClientExtractor} resolves Feign URLs; a dynamic (non-literal, non-resolvable)
 * URL is recorded as "unresolved" with its source location rather than silently dropped.
 */
public class RestClientExtractor implements Extractor {

    private static final Logger log = LoggerFactory.getLogger(RestClientExtractor.class);

    private static final Set<String> HTTP_CLIENT_TYPES = Set.of("RestTemplate", "WebClient", "OkHttpClient");

    private static final Map<String, Set<String>> CALL_METHODS_BY_CLIENT = Map.of(
            "RestTemplate", Set.of("exchange", "getForObject", "getForEntity", "postForObject", "postForEntity", "put", "delete", "patchForObject"),
            "WebClient", Set.of("uri"),
            "OkHttpClient", Set.of("newCall")
    );

    @Override
    public String name() {
        return "RestClientExtractor";
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
                log.warn("Failed extracting REST client usage from {}: {}", prov.filePath(), e.toString());
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
        Map<String, String> clientFieldsByName = clientFieldsIn(type);
        if (clientFieldsByName.isEmpty()) {
            return;
        }

        String classFqn = String.valueOf(classNode.getAttribute("fqn"));

        for (MethodDeclaration method : type.getMethods()) {
            String signature = buildSignature(method);
            String methodNodeId = NodeIds.forMethod(classFqn, signature);

            method.findAll(MethodCallExpr.class).forEach(call -> {
                String rootFieldName = rootScopeFieldName(call);
                if (rootFieldName == null) return;
                String clientType = clientFieldsByName.get(rootFieldName);
                if (clientType == null) return;
                Set<String> callMethods = CALL_METHODS_BY_CLIENT.get(clientType);
                if (!callMethods.contains(call.getNameAsString())) return;

                recordCall(context, methodNodeId, clientType, call, relativePath);
            });
        }
    }

    /** Walks a fluent call chain (e.g. {@code restTemplate.exchange(...)} or {@code webClient.get().uri(...)}) back to its root identifier. */
    private String rootScopeFieldName(MethodCallExpr call) {
        Expression scope = call.getScope().orElse(null);
        while (scope instanceof MethodCallExpr inner) {
            scope = inner.getScope().orElse(null);
        }
        if (scope instanceof com.github.javaparser.ast.expr.NameExpr nameExpr) {
            return nameExpr.getNameAsString();
        }
        if (scope instanceof com.github.javaparser.ast.expr.FieldAccessExpr fieldAccess) {
            return fieldAccess.getNameAsString();
        }
        return null;
    }

    private void recordCall(AnalysisContext context, String methodNodeId, String clientType, MethodCallExpr call, String relativePath) {
        String rawUrl = firstStringLiteralArg(call).orElse(null);
        String resolvedUrl = rawUrl != null ? resolvePlaceholder(context, rawUrl) : null;
        String identity = resolvedUrl != null ? resolvedUrl : clientType + " call (dynamic URL, unresolved)";

        Node externalService = new Node(NodeIds.forExternalService(identity), NodeType.EXTERNAL_SERVICE, identity)
                .withAttribute("via", clientType)
                .withAttribute("declaredUrl", rawUrl)
                .withProvenance(Provenance.of(relativePath, call.getBegin().map(p -> p.line).orElse(-1), name()));
        context.graph().addNode(externalService);

        context.graph().addEdge(new Edge(methodNodeId, externalService.getId(), EdgeType.DEPENDS_ON)
                .withAttribute("via", clientType)
                .withProvenance(Provenance.of(relativePath, call.getBegin().map(p -> p.line).orElse(-1), name())));
    }

    private Optional<String> firstStringLiteralArg(MethodCallExpr call) {
        for (Expression arg : call.getArguments()) {
            if (arg instanceof StringLiteralExpr str) {
                return Optional.of(str.asString());
            }
        }
        // Walk the call's own scope chain (e.g. webClient.get().uri("...")) for a literal too.
        if (call.getScope().isPresent() && call.getScope().get() instanceof MethodCallExpr inner) {
            return firstStringLiteralArg(inner);
        }
        return Optional.empty();
    }

    private String resolvePlaceholder(AnalysisContext context, String raw) {
        if (!raw.contains("${")) {
            return raw;
        }
        return context.config().resolve(raw).orElse(null);
    }

    /** Maps each client-typed field/constructor-param *name* to its client type, so calls can be traced back to a specific field. */
    private Map<String, String> clientFieldsIn(TypeDeclaration<?> type) {
        Map<String, String> found = new java.util.HashMap<>();
        for (FieldDeclaration field : type.getFields()) {
            for (VariableDeclarator var : field.getVariables()) {
                String t = stripGenerics(var.getTypeAsString());
                if (HTTP_CLIENT_TYPES.contains(t)) {
                    found.put(var.getNameAsString(), t);
                }
            }
        }
        for (var ctor : type.getConstructors()) {
            for (var param : ctor.getParameters()) {
                String t = stripGenerics(param.getTypeAsString());
                if (HTTP_CLIENT_TYPES.contains(t)) {
                    found.put(param.getNameAsString(), t);
                }
            }
        }
        return found;
    }

    private String stripGenerics(String type) {
        int idx = type.indexOf('<');
        return idx == -1 ? type : type.substring(0, idx);
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
