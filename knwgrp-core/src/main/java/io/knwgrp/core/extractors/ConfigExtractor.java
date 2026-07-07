package io.knwgrp.core.extractors;

import io.knwgrp.core.AnalysisContext;
import io.knwgrp.core.ConfigModel;
import io.knwgrp.core.Extractor;
import io.knwgrp.model.Node;
import io.knwgrp.model.NodeIds;
import io.knwgrp.model.NodeType;
import io.knwgrp.model.Provenance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.stream.Stream;

/**
 * Parses every {@code application.yml}, {@code application-<profile>.yml}, and
 * {@code application*.properties} under the repo into a flat dotted-key {@link ConfigModel},
 * and emits a {@link NodeType#CONFIG_PROPERTY} node per key so config is visible in the graph
 * even before anything in code references it.
 *
 * <p>Files are processed in a stable order with base {@code application.yml} first and
 * profile-specific files afterward, so profile overlays correctly override base values,
 * matching Spring's own precedence.
 */
public class ConfigExtractor implements Extractor {

    private static final Logger log = LoggerFactory.getLogger(ConfigExtractor.class);

    @Override
    public String name() {
        return "ConfigExtractor";
    }

    @Override
    public void extract(AnalysisContext context) {
        try (Stream<Path> files = Files.walk(context.repoRoot())) {
            List<Path> configFiles = files
                    .filter(Files::isRegularFile)
                    .filter(this::isConfigFile)
                    .filter(p -> !isInTestOrTarget(p))
                    .sorted(Comparator.comparing(this::profileRank).thenComparing(Path::toString))
                    .toList();

            for (Path file : configFiles) {
                processFile(context, file);
            }
        } catch (IOException e) {
            log.warn("Failed walking repo for config files: {}", e.toString());
        }
    }

    private boolean isConfigFile(Path p) {
        String name = p.getFileName().toString();
        return (name.startsWith("application") && (name.endsWith(".yml") || name.endsWith(".yaml") || name.endsWith(".properties")))
                || (name.startsWith("bootstrap") && (name.endsWith(".yml") || name.endsWith(".yaml") || name.endsWith(".properties")));
    }

    private boolean isInTestOrTarget(Path p) {
        String s = p.toString().replace('\\', '/');
        return s.contains("/target/") || s.contains("/build/") || s.contains("/test/resources/");
    }

    /** Base application.yml sorts before profile-specific files, so overlays apply last. */
    private int profileRank(Path p) {
        String name = p.getFileName().toString();
        boolean isBase = name.equals("application.yml") || name.equals("application.yaml") || name.equals("application.properties");
        return isBase ? 0 : 1;
    }

    private void processFile(AnalysisContext context, Path file) {
        String relativePath = context.relativize(file);
        String fileName = file.getFileName().toString();

        Node fileNode = new Node(NodeIds.forConfigProperty("__file__:" + relativePath), NodeType.CONFIG_FILE, fileName)
                .withProvenance(Provenance.ofFile(relativePath, name()));
        context.graph().addNode(fileNode);

        Map<String, Object> flat = new LinkedHashMap<>();
        try {
            if (fileName.endsWith(".properties")) {
                flat.putAll(loadProperties(file));
            } else {
                flat.putAll(flattenYaml(loadYaml(file)));
            }
        } catch (Exception e) {
            log.warn("Failed parsing config file {}: {}", relativePath, e.toString());
            return;
        }

        for (Map.Entry<String, Object> entry : flat.entrySet()) {
            String key = entry.getKey();
            String value = String.valueOf(entry.getValue());

            context.config().put(key, value, relativePath, Provenance.NO_LINE);

            Node propNode = new Node(NodeIds.forConfigProperty(key), NodeType.CONFIG_PROPERTY, key)
                    .withAttribute("value", value)
                    .withAttribute("sourceFile", relativePath)
                    .withProvenance(Provenance.ofFile(relativePath, name()));
            context.graph().addNode(propNode);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> loadYaml(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            Yaml yaml = new Yaml();
            // A YAML file may contain multiple `---`-separated documents (Spring profile documents).
            Map<String, Object> merged = new LinkedHashMap<>();
            for (Object doc : yaml.loadAll(in)) {
                if (doc instanceof Map) {
                    merged.putAll((Map<String, Object>) doc);
                }
            }
            return merged;
        }
    }

    private Map<String, String> loadProperties(Path file) throws IOException {
        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(file)) {
            props.load(in);
        }
        Map<String, String> result = new LinkedHashMap<>();
        for (String key : props.stringPropertyNames()) {
            result.put(key, props.getProperty(key));
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> flattenYaml(Map<String, Object> nested) {
        Map<String, Object> flat = new LinkedHashMap<>();
        flatten("", nested, flat);
        return flat;
    }

    @SuppressWarnings("unchecked")
    private void flatten(String prefix, Map<String, Object> map, Map<String, Object> out) {
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            String key = prefix.isEmpty() ? entry.getKey() : prefix + "." + entry.getKey();
            Object value = entry.getValue();
            if (value instanceof Map) {
                flatten(key, (Map<String, Object>) value, out);
            } else if (value instanceof List<?> list) {
                for (int i = 0; i < list.size(); i++) {
                    Object item = list.get(i);
                    if (item instanceof Map) {
                        flatten(key + "[" + i + "]", (Map<String, Object>) item, out);
                    } else {
                        out.put(key + "[" + i + "]", item);
                    }
                }
            } else if (value != null) {
                out.put(key, value);
            }
        }
    }
}
