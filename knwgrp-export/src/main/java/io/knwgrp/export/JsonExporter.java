package io.knwgrp.export;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.knwgrp.model.Edge;
import io.knwgrp.model.KnowledgeGraph;
import io.knwgrp.model.Node;
import io.knwgrp.model.Provenance;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Exports the {@link KnowledgeGraph} as the canonical JSON representation — the source-of-truth
 * format that every other exporter (Mermaid, HTML, AI-context) should be able to derive from,
 * and that downstream tools (including LLMs) can consume directly.
 */
public class JsonExporter {

    private final ObjectMapper mapper = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    public void export(KnowledgeGraph graph, Path outputFile, Path repoRoot) throws IOException {
        mapper.writeValue(outputFile.toFile(), buildRoot(graph, repoRoot));
    }

    public String toJsonString(KnowledgeGraph graph, Path repoRoot) throws IOException {
        return mapper.writeValueAsString(buildRoot(graph, repoRoot));
    }

    private Map<String, Object> buildRoot(KnowledgeGraph graph, Path repoRoot) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("nodes", graph.getNodes().stream().map(this::nodeToMap).toList());
        root.put("edges", graph.getEdges().stream().map(this::edgeToMap).toList());
        root.put("stats", Map.of("nodeCount", graph.nodeCount(), "edgeCount", graph.edgeCount()));
        // repoRoot lets the HTML explorer build "open in editor" deep links (vscode://file/<root>/<relFile>:<line>)
        root.put("meta", Map.of("repoRoot", repoRoot.toAbsolutePath().normalize().toString().replace('\\', '/')));
        return root;
    }

    private Map<String, Object> nodeToMap(Node node) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", node.getId());
        map.put("type", node.getType().name());
        map.put("name", node.getName());
        map.put("attributes", node.getAttributes());
        map.put("provenance", provenanceToMap(node.getProvenance()));
        return map;
    }

    private Map<String, Object> edgeToMap(Edge edge) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("source", edge.getSourceId());
        map.put("target", edge.getTargetId());
        map.put("type", edge.getType().name());
        map.put("attributes", edge.getAttributes());
        map.put("provenance", provenanceToMap(edge.getProvenance()));
        return map;
    }

    private Map<String, Object> provenanceToMap(Provenance provenance) {
        if (provenance == null) return null;
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("file", provenance.filePath());
        map.put("line", provenance.line());
        map.put("extractor", provenance.extractor());
        return map;
    }
}
