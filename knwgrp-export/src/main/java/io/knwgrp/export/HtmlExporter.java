package io.knwgrp.export;

import io.knwgrp.model.KnowledgeGraph;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Renders a self-contained, static HTML explorer for a {@link KnowledgeGraph}: a single file
 * with the graph JSON embedded, viewable by opening it in a browser with no server. Designed
 * for graphs too large to render as one static Mermaid diagram (hundreds to thousands of
 * nodes) — offers search, type-based drill-down, and a focused neighborhood view per node
 * rather than an all-at-once force layout.
 */
public class HtmlExporter {

    public void export(KnowledgeGraph graph, Path outputFile, Path repoRoot) throws IOException {
        String json = new JsonExporter().toJsonString(graph, repoRoot);
        String template = loadTemplate();
        String html = template.replace("/*__GRAPH_DATA__*/", "const GRAPH_DATA = " + json + ";");
        Files.writeString(outputFile, html, StandardCharsets.UTF_8);
    }

    private String loadTemplate() throws IOException {
        try (InputStream in = getClass().getResourceAsStream("/explorer-template.html")) {
            if (in == null) {
                throw new IOException("explorer-template.html not found on classpath");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
