package io.knwgrp.model;

/**
 * Records where a fact (node or edge) was discovered: which file, which line,
 * and which extractor produced it. Every edge in the graph must be traceable
 * back to source so a human (or tool) can verify it independently.
 *
 * @param filePath   path to the source/config file, relative to the analyzed repo root
 * @param line       1-based line number, or -1 if not applicable
 * @param extractor  the simple name of the extractor that produced this fact
 */
public record Provenance(String filePath, int line, String extractor) {

    public static final int NO_LINE = -1;

    public static Provenance of(String filePath, int line, String extractor) {
        return new Provenance(filePath, line, extractor);
    }

    public static Provenance ofFile(String filePath, String extractor) {
        return new Provenance(filePath, NO_LINE, extractor);
    }
}
