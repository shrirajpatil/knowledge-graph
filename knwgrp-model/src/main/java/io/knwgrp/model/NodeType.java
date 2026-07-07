package io.knwgrp.model;

/**
 * The kind of entity a {@link Node} represents in the knowledge graph.
 */
public enum NodeType {
    SYSTEM,
    SERVICE,
    MODULE,
    PACKAGE,
    CLASS,
    INTERFACE,
    ENUM,
    METHOD,
    FIELD,
    ANNOTATION,

    ENDPOINT,
    ENTITY,
    TABLE,
    DTO,

    REPOSITORY,
    KAFKA_TOPIC,
    QUEUE,
    DATABASE,
    EXTERNAL_SERVICE,
    FEIGN_CLIENT,

    CONFIG_PROPERTY,
    CONFIG_FILE,
    SCHEDULED_JOB,
    CACHE,
    BEAN
}
