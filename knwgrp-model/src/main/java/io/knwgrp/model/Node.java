package io.knwgrp.model;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * A single entity in the knowledge graph (a class, method, endpoint, Kafka topic, etc).
 *
 * <p>{@code id} is a stable, globally unique identifier (e.g. fully-qualified name for a class,
 * {@code "METHOD:com.foo.Bar#baz(int)"} for a method, {@code "TOPIC:order-events"} for a Kafka
 * topic). Callers are responsible for constructing ids consistently; see {@link NodeIds}.
 *
 * <p>{@code attributes} holds type-specific, loosely-structured data (e.g. an ENDPOINT node
 * carries {@code httpMethod} and {@code path}; a CONFIG_PROPERTY node carries {@code value}).
 * This keeps the core model stable while individual extractors evolve independently.
 */
public class Node {

    private final String id;
    private final NodeType type;
    private final String name;
    private final Map<String, Object> attributes = new LinkedHashMap<>();
    private Provenance provenance;

    public Node(String id, NodeType type, String name) {
        this.id = Objects.requireNonNull(id, "id");
        this.type = Objects.requireNonNull(type, "type");
        this.name = Objects.requireNonNull(name, "name");
    }

    public String getId() {
        return id;
    }

    public NodeType getType() {
        return type;
    }

    public String getName() {
        return name;
    }

    public Provenance getProvenance() {
        return provenance;
    }

    public Node withProvenance(Provenance provenance) {
        this.provenance = provenance;
        return this;
    }

    public Node withAttribute(String key, Object value) {
        attributes.put(key, value);
        return this;
    }

    public Map<String, Object> getAttributes() {
        return attributes;
    }

    public Object getAttribute(String key) {
        return attributes.get(key);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Node node)) return false;
        return id.equals(node.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    @Override
    public String toString() {
        return type + ":" + id;
    }
}
