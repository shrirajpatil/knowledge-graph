package io.knwgrp.export;

import io.knwgrp.model.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Renders diffable Mermaid diagrams from the graph, checked-in-friendly for PR review of
 * architectural changes. Two views are supported:
 *
 * <ul>
 *   <li>{@link #componentDiagram} — controllers/services/repositories/external systems and how
 *       they wire together (INJECTS/DEPENDS_ON edges), roughly a C4 "component" level view.</li>
 *   <li>{@link #entityRelationshipDiagram} — tables and their JPA relationships.</li>
 * </ul>
 */
public class MermaidExporter {

    public void componentDiagram(KnowledgeGraph graph, Path outputFile) throws IOException {
        Files.writeString(outputFile, componentDiagram(graph));
    }

    public String componentDiagram(KnowledgeGraph graph) {
        StringBuilder sb = new StringBuilder("graph TD\n");
        Set<String> declared = new LinkedHashSet<>();

        for (Node node : graph.getNodes()) {
            if (node.getType() == NodeType.CLASS || node.getType() == NodeType.INTERFACE) {
                String stereotype = String.valueOf(node.getAttribute("springStereotype"));
                if ("plain".equals(stereotype) || "null".equals(stereotype)) continue;
                declareNode(sb, declared, node.getId(), node.getName(), shapeFor(stereotype));
            } else if (node.getType() == NodeType.EXTERNAL_SERVICE
                    || node.getType() == NodeType.KAFKA_TOPIC
                    || node.getType() == NodeType.TABLE
                    || node.getType() == NodeType.QUEUE) {
                declareNode(sb, declared, node.getId(), node.getName(), shapeForNodeType(node.getType()));
            }
        }

        for (Edge edge : graph.getEdges()) {
            if (!declared.contains(edge.getSourceId()) || !declared.contains(edge.getTargetId())) continue;
            if (edge.getType() == EdgeType.INJECTS || edge.getType() == EdgeType.DEPENDS_ON
                    || edge.getType() == EdgeType.PUBLISHES_TO || edge.getType() == EdgeType.LISTENS_TO
                    || edge.getType() == EdgeType.MAPS_TO) {
                sb.append("    ").append(sanitize(edge.getSourceId()))
                        .append(" -->|").append(edge.getType().name().toLowerCase()).append("| ")
                        .append(sanitize(edge.getTargetId())).append("\n");
            }
        }

        return sb.toString();
    }

    public void entityRelationshipDiagram(KnowledgeGraph graph, Path outputFile) throws IOException {
        Files.writeString(outputFile, entityRelationshipDiagram(graph));
    }

    public String entityRelationshipDiagram(KnowledgeGraph graph) {
        StringBuilder sb = new StringBuilder("graph LR\n");
        Set<String> declared = new LinkedHashSet<>();

        List<Node> tables = graph.getNodesByType(NodeType.TABLE);
        for (Node table : tables) {
            declareNode(sb, declared, table.getId(), table.getName(), "[(%s)]");
        }

        for (Edge edge : graph.getEdgesByType(EdgeType.DEPENDS_ON)) {
            if (!declared.contains(edge.getSourceId()) || !declared.contains(edge.getTargetId())) continue;
            String via = String.valueOf(edge.getAttributes().getOrDefault("via", ""));
            sb.append("    ").append(sanitize(edge.getSourceId()))
                    .append(" -->|").append(via).append("| ")
                    .append(sanitize(edge.getTargetId())).append("\n");
        }

        return sb.toString();
    }

    private void declareNode(StringBuilder sb, Set<String> declared, String id, String label, String shapeTemplate) {
        if (declared.contains(id)) return;
        declared.add(id);
        String safeId = sanitize(id);
        sb.append("    ").append(safeId).append(String.format(shapeTemplate, label)).append("\n");
    }

    private String shapeFor(String stereotype) {
        return switch (stereotype) {
            case "controller" -> "[%s]";
            case "service" -> "(%s)";
            case "repository" -> "[(%s)]";
            case "feignClient" -> "{{%s}}";
            default -> "[%s]";
        };
    }

    private String shapeForNodeType(NodeType type) {
        return switch (type) {
            case EXTERNAL_SERVICE -> "((%s))";
            case KAFKA_TOPIC -> ">%s]";
            case TABLE -> "[(%s)]";
            case QUEUE -> ">%s]";
            default -> "[%s]";
        };
    }

    private String sanitize(String id) {
        return id.replaceAll("[^a-zA-Z0-9]", "_");
    }
}
