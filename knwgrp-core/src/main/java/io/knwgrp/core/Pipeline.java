package io.knwgrp.core;

import io.knwgrp.model.KnowledgeGraph;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Runs a fixed, ordered list of {@link Extractor}s over a repository and returns the resulting
 * graph. Extractors are additive and order matters only in that later extractors may rely on
 * nodes created earlier (e.g. the Spring layer expects classes/methods to already exist).
 *
 * <p>A single failing extractor logs and is skipped rather than aborting the whole run, since
 * partial knowledge is more useful than none for a documentation tool.
 */
public class Pipeline {

    private static final Logger log = LoggerFactory.getLogger(Pipeline.class);

    private final List<Extractor> extractors = new ArrayList<>();

    public Pipeline addExtractor(Extractor extractor) {
        extractors.add(extractor);
        return this;
    }

    public KnowledgeGraph run(Path repoRoot) {
        KnowledgeGraph graph = new KnowledgeGraph();
        ConfigModel config = new ConfigModel();
        AnalysisContext context = new AnalysisContext(repoRoot, graph, config);

        for (Extractor extractor : extractors) {
            log.info("Running extractor: {}", extractor.name());
            try {
                extractor.extract(context);
            } catch (Exception e) {
                log.warn("Extractor {} failed: {}", extractor.name(), e.toString());
            }
        }

        log.info("Analysis complete: {} nodes, {} edges", graph.nodeCount(), graph.edgeCount());
        return graph;
    }
}
