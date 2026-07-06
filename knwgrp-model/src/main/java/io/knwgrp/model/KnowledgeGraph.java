package io.knwgrp.model;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * The canonical, mutable graph model that all extractors write into and all exporters read
 * from. Nodes are keyed by id and merged (not overwritten) when added twice, since multiple
 * extractors may independently discover the same entity (e.g. both the JPA extractor and the
 * plain class scan will see the same {@code @Entity} class).
 */
public class KnowledgeGraph {

    private final Map<String, Node> nodes = new LinkedHashMap<>();
    private final List<Edge> edges = new ArrayList<>();

    public Node addNode(Node node) {
        Node existing = nodes.get(node.getId());
        if (existing == null) {
            nodes.put(node.getId(), node);
            return node;
        }
        // Merge attributes from the new node into the existing one; first-seen provenance wins.
        node.getAttributes().forEach(existing::withAttribute);
        if (existing.getProvenance() == null && node.getProvenance() != null) {
            existing.withProvenance(node.getProvenance());
        }
        return existing;
    }

    public void addEdge(Edge edge) {
        if (!edges.contains(edge)) {
            edges.add(edge);
        }
    }

    public Optional<Node> findNode(String id) {
        return Optional.ofNullable(nodes.get(id));
    }

    public boolean hasNode(String id) {
        return nodes.containsKey(id);
    }

    public Collection<Node> getNodes() {
        return nodes.values();
    }

    public List<Edge> getEdges() {
        return edges;
    }

    public List<Node> getNodesByType(NodeType type) {
        return nodes.values().stream()
                .filter(n -> n.getType() == type)
                .collect(Collectors.toList());
    }

    public List<Edge> getEdgesByType(EdgeType type) {
        return edges.stream()
                .filter(e -> e.getType() == type)
                .collect(Collectors.toList());
    }

    public List<Edge> outgoingEdges(String nodeId) {
        return edges.stream()
                .filter(e -> e.getSourceId().equals(nodeId))
                .collect(Collectors.toList());
    }

    public List<Edge> incomingEdges(String nodeId) {
        return edges.stream()
                .filter(e -> e.getTargetId().equals(nodeId))
                .collect(Collectors.toList());
    }

    public int nodeCount() {
        return nodes.size();
    }

    public int edgeCount() {
        return edges.size();
    }
}
