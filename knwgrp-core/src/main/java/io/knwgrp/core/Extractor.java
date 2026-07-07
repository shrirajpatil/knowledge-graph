package io.knwgrp.core;

/**
 * SPI implemented by every analysis stage (AST extraction, Spring semantics, Feign, Kafka,
 * JPA, config resolution, ...). Extractors run in a defined order (see {@link Pipeline}) and
 * each contributes nodes/edges to the shared {@link io.knwgrp.model.KnowledgeGraph}.
 *
 * <p>Extractors should be tolerant of partial/missing information: prefer emitting a node with
 * an "unresolved" attribute over throwing, since one file failing to parse should not abort the
 * whole analysis run.
 */
public interface Extractor {

    /** Short, stable name used in {@link io.knwgrp.model.Provenance#extractor()}. */
    String name();

    void extract(AnalysisContext context);
}
