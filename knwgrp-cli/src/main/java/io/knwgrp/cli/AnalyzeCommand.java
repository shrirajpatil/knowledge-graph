package io.knwgrp.cli;

import io.knwgrp.core.Pipeline;
import io.knwgrp.core.extractors.ConfigExtractor;
import io.knwgrp.export.HtmlExporter;
import io.knwgrp.export.JsonExporter;
import io.knwgrp.export.MermaidExporter;
import io.knwgrp.java.JavaSourceExtractor;
import io.knwgrp.model.KnowledgeGraph;
import io.knwgrp.spring.*;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;

/**
 * {@code cartograph analyze <repo>} — runs the full extraction pipeline over a Spring Boot
 * repository and writes the resulting knowledge graph as JSON plus Mermaid component and
 * entity-relationship diagrams.
 */
@Command(name = "analyze", description = "Analyze a Spring Boot microservice repository and generate its knowledge graph.")
public class AnalyzeCommand implements Callable<Integer> {

    @Parameters(index = "0", description = "Path to the repository root to analyze.")
    private Path repoPath;

    @Option(names = {"-o", "--output"}, description = "Output directory for generated artifacts.", defaultValue = "cartograph-output")
    private Path outputDir;

    @Override
    public Integer call() throws Exception {
        if (!Files.isDirectory(repoPath)) {
            System.err.println("Not a directory: " + repoPath);
            return 1;
        }
        Files.createDirectories(outputDir);

        Pipeline pipeline = new Pipeline()
                .addExtractor(new ConfigExtractor())
                .addExtractor(new JavaSourceExtractor())
                .addExtractor(new StereotypeExtractor())
                .addExtractor(new EndpointExtractor())
                .addExtractor(new DependencyInjectionExtractor())
                .addExtractor(new JpaEntityExtractor())
                .addExtractor(new FeignClientExtractor())
                .addExtractor(new KafkaExtractor())
                .addExtractor(new RestClientExtractor())
                .addExtractor(new RedisExtractor());

        Path absoluteRepoRoot = repoPath.toAbsolutePath().normalize();
        KnowledgeGraph graph = pipeline.run(absoluteRepoRoot);

        JsonExporter jsonExporter = new JsonExporter();
        jsonExporter.export(graph, outputDir.resolve("graph.json"), absoluteRepoRoot);

        MermaidExporter mermaidExporter = new MermaidExporter();
        mermaidExporter.componentDiagram(graph, outputDir.resolve("component-diagram.mmd"));
        mermaidExporter.entityRelationshipDiagram(graph, outputDir.resolve("entity-diagram.mmd"));

        HtmlExporter htmlExporter = new HtmlExporter();
        htmlExporter.export(graph, outputDir.resolve("explorer.html"), absoluteRepoRoot);

        System.out.printf("Analyzed %s%n", repoPath);
        System.out.printf("  %d nodes, %d edges%n", graph.nodeCount(), graph.edgeCount());
        System.out.printf("  Output written to %s%n", outputDir.toAbsolutePath());
        System.out.printf("  Open %s in a browser to explore%n", outputDir.resolve("explorer.html").toAbsolutePath());

        return 0;
    }
}
