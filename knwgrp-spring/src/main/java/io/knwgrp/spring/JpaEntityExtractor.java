package io.knwgrp.spring;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.FieldDeclaration;
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

/**
 * For every class tagged {@code springStereotype=entity} (has {@code @Entity}), extracts the
 * backing table name ({@code @Table(name=...)}, defaulting to the snake_case class name per
 * Hibernate's default naming strategy) and produces a TABLE node plus a MAPS_TO edge. Also
 * detects {@code @OneToMany}/{@code @ManyToOne}/{@code @OneToOne}/{@code @ManyToMany} fields
 * and records them as DEPENDS_ON edges between the two entities' tables, giving a first-order
 * ER diagram for free.
 */
public class JpaEntityExtractor implements Extractor {

    private static final Logger log = LoggerFactory.getLogger(JpaEntityExtractor.class);

    private static final List<String> RELATIONSHIP_ANNOTATIONS =
            List.of("OneToMany", "ManyToOne", "OneToOne", "ManyToMany");

    @Override
    public String name() {
        return "JpaEntityExtractor";
    }

    @Override
    public void extract(AnalysisContext context) {
        List<Node> entities = context.graph().getNodes().stream()
                .filter(n -> n.getType() == NodeType.CLASS)
                .filter(n -> "entity".equals(n.getAttribute("springStereotype")))
                .toList();

        for (Node entity : entities) {
            Provenance prov = entity.getProvenance();
            if (prov == null) continue;
            Path file = context.repoRoot().resolve(prov.filePath());
            if (!Files.exists(file)) continue;

            try {
                processFile(context, file, prov.filePath(), entity);
            } catch (Exception e) {
                log.warn("Failed extracting JPA entity from {}: {}", prov.filePath(), e.toString());
            }
        }
    }

    private void processFile(AnalysisContext context, Path file, String relativePath, Node entityNode) throws IOException {
        CompilationUnit cu = StaticJavaParser.parse(file);
        String simpleName = entityNode.getName();

        cu.getTypes().stream()
                .filter(t -> t.getNameAsString().equals(simpleName))
                .findFirst()
                .ifPresent(type -> processType(context, type, relativePath, entityNode));
    }

    private void processType(AnalysisContext context, TypeDeclaration<?> type, String relativePath, Node entityNode) {
        String tableName = tableNameFor(type, entityNode.getName());
        String classFqn = String.valueOf(entityNode.getAttribute("fqn"));

        Node tableNode = new Node(NodeIds.forTable(tableName), NodeType.TABLE, tableName)
                .withProvenance(Provenance.of(relativePath, type.getBegin().map(p -> p.line).orElse(-1), name()));
        context.graph().addNode(tableNode);

        context.graph().addEdge(new Edge(NodeIds.forClass(classFqn), tableNode.getId(), EdgeType.MAPS_TO)
                .withProvenance(Provenance.of(relativePath, type.getBegin().map(p -> p.line).orElse(-1), name())));

        for (FieldDeclaration field : type.getFields()) {
            for (AnnotationExpr ann : field.getAnnotations()) {
                if (RELATIONSHIP_ANNOTATIONS.contains(ann.getNameAsString())) {
                    recordRelationship(context, tableNode.getId(), field, relativePath);
                }
            }
        }
    }

    private void recordRelationship(AnalysisContext context, String sourceTableId, FieldDeclaration field, String relativePath) {
        field.getVariables().forEach(var -> {
            String declaredType = stripCollectionGeneric(var.getTypeAsString());
            String targetTable = camelToSnake(declaredType);
            context.graph().addEdge(new Edge(sourceTableId, NodeIds.forTable(targetTable), EdgeType.DEPENDS_ON)
                    .withAttribute("via", field.getAnnotations().get(0).getNameAsString())
                    .withProvenance(Provenance.of(relativePath, field.getBegin().map(p -> p.line).orElse(-1), name())));
        });
    }

    private String stripCollectionGeneric(String type) {
        int start = type.indexOf('<');
        if (start == -1) return type;
        int end = type.lastIndexOf('>');
        return end > start ? type.substring(start + 1, end) : type;
    }

    private String tableNameFor(TypeDeclaration<?> type, String className) {
        for (AnnotationExpr ann : type.getAnnotations()) {
            if (ann.getNameAsString().equals("Table") && ann instanceof NormalAnnotationExpr normal) {
                for (MemberValuePair pair : normal.getPairs()) {
                    if (pair.getNameAsString().equals("name") && pair.getValue() instanceof StringLiteralExpr str) {
                        return str.asString();
                    }
                }
            }
        }
        return camelToSnake(className);
    }

    private String camelToSnake(String s) {
        return s.replaceAll("([a-z])([A-Z])", "$1_$2").toLowerCase();
    }
}
