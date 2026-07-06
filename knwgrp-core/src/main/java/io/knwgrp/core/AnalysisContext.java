package io.knwgrp.core;

import io.knwgrp.model.KnowledgeGraph;

import java.nio.file.Path;

/**
 * Shared state passed to every {@link Extractor} during a single analysis run: the repo
 * root, the graph being built, and the resolved configuration properties (so extractors can
 * resolve {@code ${placeholder}} references without each re-parsing YAML themselves).
 */
public class AnalysisContext {

    private final Path repoRoot;
    private final KnowledgeGraph graph;
    private final ConfigModel config;

    public AnalysisContext(Path repoRoot, KnowledgeGraph graph, ConfigModel config) {
        this.repoRoot = repoRoot;
        this.graph = graph;
        this.config = config;
    }

    public Path repoRoot() {
        return repoRoot;
    }

    public KnowledgeGraph graph() {
        return graph;
    }

    public ConfigModel config() {
        return config;
    }

    /** Relativizes an absolute path against the repo root, for use in {@link io.knwgrp.model.Provenance}. */
    public String relativize(Path absolutePath) {
        try {
            return repoRoot.relativize(absolutePath).toString().replace('\\', '/');
        } catch (IllegalArgumentException e) {
            return absolutePath.toString().replace('\\', '/');
        }
    }
}
