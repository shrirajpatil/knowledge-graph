package io.knwgrp.core;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Flattened view of every {@code application*.yml}/{@code .properties} file found in the repo,
 * across all profiles, keyed by dotted property name (Spring relaxed-binding style, e.g.
 * {@code "payment.service.url"}). Used by extractors to resolve {@code @Value("${...}")} and
 * {@code ${...}} references in Feign/datasource/Kafka config to their concrete values.
 *
 * <p>Later-loaded profiles overlay (override) earlier ones, mirroring Spring's own precedence
 * for {@code application-<profile>.yml} over the base {@code application.yml}.
 */
public class ConfigModel {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([^:}]+)(?::([^}]*))?}");
    private static final int MAX_RESOLUTION_DEPTH = 10;

    /** property key -> (value, source file it was defined in) */
    private final Map<String, PropertyValue> properties = new LinkedHashMap<>();

    public record PropertyValue(String value, String sourceFile, int line) {
    }

    public void put(String key, String value, String sourceFile, int line) {
        properties.put(key, new PropertyValue(value, sourceFile, line));
    }

    public Optional<PropertyValue> get(String key) {
        return Optional.ofNullable(properties.get(key));
    }

    public Map<String, PropertyValue> all() {
        return properties;
    }

    /**
     * Resolves {@code ${a.b.c}} and {@code ${a.b.c:default}} placeholders in the given raw
     * string against known properties, recursively. Returns {@link Optional#empty()} if the
     * value contains a placeholder that cannot be resolved and has no default — callers should
     * treat that as "dynamic/unresolved" rather than guessing.
     */
    public Optional<String> resolve(String raw) {
        return resolve(raw, 0);
    }

    private Optional<String> resolve(String raw, int depth) {
        if (raw == null) {
            return Optional.empty();
        }
        if (depth > MAX_RESOLUTION_DEPTH) {
            return Optional.empty();
        }
        Matcher matcher = PLACEHOLDER.matcher(raw);
        StringBuilder result = new StringBuilder();
        int last = 0;
        boolean fullyResolved = true;

        while (matcher.find()) {
            result.append(raw, last, matcher.start());
            String key = matcher.group(1);
            String defaultValue = matcher.group(2);

            Optional<PropertyValue> resolved = get(key);
            if (resolved.isPresent()) {
                Optional<String> nested = resolve(resolved.get().value(), depth + 1);
                if (nested.isPresent()) {
                    result.append(nested.get());
                } else if (defaultValue != null) {
                    result.append(defaultValue);
                } else {
                    fullyResolved = false;
                }
            } else if (defaultValue != null) {
                result.append(defaultValue);
            } else {
                fullyResolved = false;
            }
            last = matcher.end();
        }
        result.append(raw.substring(last));

        return fullyResolved ? Optional.of(result.toString()) : Optional.empty();
    }
}
