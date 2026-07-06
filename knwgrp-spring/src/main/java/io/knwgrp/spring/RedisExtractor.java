package io.knwgrp.spring;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
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
 * Detects Redis usage via injected {@code RedisTemplate}/{@code StringRedisTemplate}/
 * {@code ReactiveRedisTemplate} fields — the standard Spring Data Redis client — and produces
 * one DATABASE node per class using it plus a DEPENDS_ON edge. Field-level rather than
 * call-site detection: a class holding one of these fields is treated as Redis-dependent
 * regardless of which specific ops (opsForValue, opsForHash, ...) it calls, since precisely
 * which operations run is a data-flow question out of scope for static field/type analysis.
 */
public class RedisExtractor implements Extractor {

    private static final Logger log = LoggerFactory.getLogger(RedisExtractor.class);

    private static final List<String> REDIS_CLIENT_TYPES = List.of("RedisTemplate", "StringRedisTemplate", "ReactiveRedisTemplate");

    @Override
    public String name() {
        return "RedisExtractor";
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
                log.warn("Failed extracting Redis usage from {}: {}", prov.filePath(), e.toString());
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
        boolean usesRedis = false;
        for (FieldDeclaration field : type.getFields()) {
            for (VariableDeclarator var : field.getVariables()) {
                String t = stripGenerics(var.getTypeAsString());
                if (REDIS_CLIENT_TYPES.contains(t)) {
                    usesRedis = true;
                }
            }
        }
        if (!usesRedis) {
            return;
        }

        Node redisNode = new Node(NodeIds.forDatabase("redis"), NodeType.DATABASE, "Redis")
                .withAttribute("engine", "redis")
                .withProvenance(Provenance.of(relativePath, type.getBegin().map(p -> p.line).orElse(-1), name()));
        context.graph().addNode(redisNode);

        String classFqn = String.valueOf(classNode.getAttribute("fqn"));
        context.graph().addEdge(new Edge(NodeIds.forClass(classFqn), redisNode.getId(), EdgeType.DEPENDS_ON)
                .withAttribute("via", "RedisTemplate")
                .withProvenance(Provenance.of(relativePath, type.getBegin().map(p -> p.line).orElse(-1), name())));
    }

    private String stripGenerics(String type) {
        int idx = type.indexOf('<');
        return idx == -1 ? type : type.substring(0, idx);
    }
}
