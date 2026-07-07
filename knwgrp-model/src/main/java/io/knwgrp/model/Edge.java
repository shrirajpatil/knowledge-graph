package io.knwgrp.model;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * A directed, typed relationship between two nodes, identified by node id (not object
 * reference) so edges can be built before both endpoints are known and resolved later.
 */
public class Edge {

    private final String sourceId;
    private final String targetId;
    private final EdgeType type;
    private final Map<String, Object> attributes = new LinkedHashMap<>();
    private Provenance provenance;

    public Edge(String sourceId, String targetId, EdgeType type) {
        this.sourceId = Objects.requireNonNull(sourceId, "sourceId");
        this.targetId = Objects.requireNonNull(targetId, "targetId");
        this.type = Objects.requireNonNull(type, "type");
    }

    public String getSourceId() {
        return sourceId;
    }

    public String getTargetId() {
        return targetId;
    }

    public EdgeType getType() {
        return type;
    }

    public Provenance getProvenance() {
        return provenance;
    }

    public Edge withProvenance(Provenance provenance) {
        this.provenance = provenance;
        return this;
    }

    public Edge withAttribute(String key, Object value) {
        attributes.put(key, value);
        return this;
    }

    public Map<String, Object> getAttributes() {
        return attributes;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Edge edge)) return false;
        return sourceId.equals(edge.sourceId)
                && targetId.equals(edge.targetId)
                && type == edge.type
                && attributes.equals(edge.attributes);
    }

    @Override
    public int hashCode() {
        return Objects.hash(sourceId, targetId, type, attributes);
    }

    @Override
    public String toString() {
        return sourceId + " -[" + type + "]-> " + targetId;
    }
}
