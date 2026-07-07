package io.knwgrp.spring;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Re-walks source files for classes tagged {@code springStereotype=controller} (by
 * {@link StereotypeExtractor}) and extracts full REST endpoint definitions: HTTP method, path
 * (merging class-level and method-level {@code @RequestMapping}), and the handler method it
 * maps to. Produces one ENDPOINT node per route plus an EXPOSES edge from the handler method.
 *
 * <p>Re-parses source rather than reusing {@code knwgrp-java}'s nodes because annotation
 * *arguments* (the path string, the HTTP verb) aren't captured by the structural extractor —
 * only annotation names are. This keeps {@code knwgrp-java} generic and pushes Spring-specific
 * annotation interpretation here where it belongs.
 */
public class EndpointExtractor implements Extractor {

    private static final Logger log = LoggerFactory.getLogger(EndpointExtractor.class);

    @Override
    public String name() {
        return "EndpointExtractor";
    }

    @Override
    public void extract(AnalysisContext context) {
        List<Node> controllers = new ArrayList<>(context.graph().getNodesByType(NodeType.CLASS).stream()
                .filter(n -> "controller".equals(n.getAttribute("springStereotype")))
                .toList());
        controllers.addAll(context.graph().getNodesByType(NodeType.INTERFACE).stream()
                .filter(n -> "controller".equals(n.getAttribute("springStereotype")))
                .toList());

        for (Node controller : controllers) {
            Provenance prov = controller.getProvenance();
            if (prov == null) continue;
            Path file = context.repoRoot().resolve(prov.filePath());
            if (!Files.exists(file)) continue;

            try {
                processControllerFile(context, file, prov.filePath(), controller);
            } catch (Exception e) {
                log.warn("Failed extracting endpoints from {}: {}", prov.filePath(), e.toString());
            }
        }
    }

    private void processControllerFile(AnalysisContext context, Path file, String relativePath, Node controllerNode) throws IOException {
        CompilationUnit cu = StaticJavaParser.parse(file);
        String simpleName = controllerNode.getName();

        cu.getTypes().stream()
                .filter(t -> t.getNameAsString().equals(simpleName))
                .findFirst()
                .ifPresent(type -> processType(context, type, relativePath, controllerNode));
    }

    private void processType(AnalysisContext context, TypeDeclaration<?> type, String relativePath, Node controllerNode) {
        List<String> classPaths = mappingPaths(type.getAnnotations());
        String classPath = classPaths.isEmpty() ? "" : classPaths.get(0);

        for (MethodDeclaration method : type.getMethods()) {
            for (AnnotationExpr ann : method.getAnnotations()) {
                if (!SpringStereotypes.MAPPING_ANNOTATIONS.contains(ann.getNameAsString())) continue;

                List<String> httpMethods = httpMethodsFor(ann);
                if (httpMethods.isEmpty()) continue;

                List<String> methodPaths = mappingPathsFromAnnotation(ann);
                List<String> fullPaths = methodPaths.isEmpty()
                        ? List.of(joinPath(classPath, ""))
                        : methodPaths.stream().map(mp -> joinPath(classPath, mp)).toList();

                for (String httpMethod : httpMethods) {
                    for (String fullPath : fullPaths) {
                        createEndpoint(context, httpMethod, fullPath, method, relativePath, controllerNode);
                    }
                }
            }
        }
    }

    private void createEndpoint(AnalysisContext context, String httpMethod, String path, MethodDeclaration method, String relativePath, Node controllerNode) {
        String endpointId = NodeIds.forEndpoint(httpMethod, path);

        String requestBodyType = null;
        List<String> pathParams = new ArrayList<>();
        List<String> queryParams = new ArrayList<>();
        for (Parameter p : method.getParameters()) {
            List<String> paramAnnotations = p.getAnnotations().stream().map(AnnotationExpr::getNameAsString).toList();
            String entry = p.getTypeAsString() + " " + p.getNameAsString();
            if (paramAnnotations.contains("RequestBody")) {
                requestBodyType = p.getTypeAsString();
            } else if (paramAnnotations.contains("PathVariable")) {
                pathParams.add(entry);
            } else if (paramAnnotations.contains("RequestParam")) {
                queryParams.add(entry);
            }
        }

        Node endpointNode = new Node(endpointId, NodeType.ENDPOINT, httpMethod + " " + path)
                .withAttribute("httpMethod", httpMethod)
                .withAttribute("path", path)
                .withAttribute("handlerClass", controllerNode.getAttribute("fqn"))
                .withAttribute("handlerMethod", method.getNameAsString())
                .withAttribute("parameters", method.getParameters().stream().map(p -> p.getTypeAsString() + " " + p.getNameAsString()).toList())
                .withAttribute("requestBodyType", requestBodyType)
                .withAttribute("pathParams", pathParams)
                .withAttribute("queryParams", queryParams)
                .withAttribute("returnType", method.getTypeAsString())
                .withProvenance(Provenance.of(relativePath, method.getBegin().map(p -> p.line).orElse(-1), name()));

        context.graph().addNode(endpointNode);

        String classFqn = String.valueOf(controllerNode.getAttribute("fqn"));
        String signature = buildSignature(method);
        String methodNodeId = NodeIds.forMethod(classFqn, signature);

        context.graph().addEdge(new Edge(methodNodeId, endpointId, EdgeType.EXPOSES)
                .withProvenance(Provenance.of(relativePath, method.getBegin().map(p -> p.line).orElse(-1), name())));
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

    private List<String> httpMethodsFor(AnnotationExpr ann) {
        String annotationName = ann.getNameAsString();
        return switch (annotationName) {
            case "GetMapping" -> List.of("GET");
            case "PostMapping" -> List.of("POST");
            case "PutMapping" -> List.of("PUT");
            case "DeleteMapping" -> List.of("DELETE");
            case "PatchMapping" -> List.of("PATCH");
            case "RequestMapping" -> requestMappingVerbs(ann);
            default -> List.of();
        };
    }

    /** {@code @RequestMapping(method = {GET, POST})} declares an explicit verb list; bare {@code @RequestMapping} defaults to GET. */
    private List<String> requestMappingVerbs(AnnotationExpr ann) {
        if (!(ann instanceof NormalAnnotationExpr normal)) {
            return List.of("GET");
        }
        for (MemberValuePair pair : normal.getPairs()) {
            if (pair.getNameAsString().equals("method")) {
                List<String> verbs = new ArrayList<>();
                collectVerbNames(pair.getValue(), verbs);
                return verbs.isEmpty() ? List.of("GET") : verbs;
            }
        }
        return List.of("GET");
    }

    private void collectVerbNames(Expression expr, List<String> out) {
        if (expr instanceof FieldAccessExpr fieldAccess) {
            out.add(fieldAccess.getNameAsString());
        } else if (expr instanceof NameExpr nameExpr) {
            out.add(nameExpr.getNameAsString());
        } else if (expr instanceof ArrayInitializerExpr array) {
            for (Expression e : array.getValues()) {
                collectVerbNames(e, out);
            }
        }
    }

    private List<String> mappingPaths(List<AnnotationExpr> annotations) {
        for (AnnotationExpr ann : annotations) {
            if (SpringStereotypes.MAPPING_ANNOTATIONS.contains(ann.getNameAsString())) {
                return mappingPathsFromAnnotation(ann);
            }
        }
        return List.of();
    }

    private List<String> mappingPathsFromAnnotation(AnnotationExpr ann) {
        List<String> paths = new ArrayList<>();
        if (ann instanceof SingleMemberAnnotationExpr single) {
            extractStringValues(single.getMemberValue(), paths);
        } else if (ann instanceof NormalAnnotationExpr normal) {
            for (MemberValuePair pair : normal.getPairs()) {
                if (pair.getNameAsString().equals("value") || pair.getNameAsString().equals("path")) {
                    extractStringValues(pair.getValue(), paths);
                }
            }
        }
        return paths;
    }

    private void extractStringValues(Expression expr, List<String> out) {
        if (expr instanceof StringLiteralExpr str) {
            out.add(str.asString());
        } else if (expr instanceof ArrayInitializerExpr array) {
            for (Expression e : array.getValues()) {
                extractStringValues(e, out);
            }
        }
    }

    private String joinPath(String classPath, String methodPath) {
        String a = normalize(classPath);
        String b = normalize(methodPath);
        if (a.isEmpty()) return b.isEmpty() ? "/" : b;
        if (b.isEmpty()) return a;
        return a + b;
    }

    private String normalize(String path) {
        if (path == null || path.isEmpty()) return "";
        String p = path.startsWith("/") ? path : "/" + path;
        return p.endsWith("/") && p.length() > 1 ? p.substring(0, p.length() - 1) : p;
    }
}
