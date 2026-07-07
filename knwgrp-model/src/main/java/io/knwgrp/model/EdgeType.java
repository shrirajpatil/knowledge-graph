package io.knwgrp.model;

/**
 * The kind of relationship an {@link Edge} represents between two nodes.
 */
public enum EdgeType {
    CONTAINS,
    CALLS,
    INJECTS,
    IMPLEMENTS,
    EXTENDS,
    ANNOTATED_WITH,

    EXPOSES,
    HAS_PARAMETER,
    RETURNS,

    CONSUMES,
    PUBLISHES_TO,
    LISTENS_TO,

    READS_FROM,
    WRITES_TO,
    MAPS_TO,

    CONFIGURED_BY,
    DEPENDS_ON,

    PUBLISHES_EVENT,
    LISTENS_TO_EVENT,

    SCHEDULED_AS
}
