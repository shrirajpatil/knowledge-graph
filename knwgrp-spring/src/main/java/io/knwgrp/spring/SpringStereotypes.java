package io.knwgrp.spring;

import java.util.Set;

/**
 * Well-known Spring annotation simple names used across the semantic layer to classify
 * classes into architectural roles (controller/service/repository/...), independent of
 * whichever extractor is asking. Kept centralized so recognizing a new stereotype only
 * requires touching one file.
 */
public final class SpringStereotypes {

    private SpringStereotypes() {
    }

    public static final Set<String> CONTROLLER = Set.of("RestController", "Controller");
    public static final Set<String> SERVICE = Set.of("Service");
    public static final Set<String> REPOSITORY = Set.of("Repository");
    public static final Set<String> COMPONENT = Set.of("Component");
    public static final Set<String> CONFIGURATION = Set.of("Configuration");
    public static final Set<String> ENTITY = Set.of("Entity", "Document", "Table");
    public static final Set<String> FEIGN_CLIENT = Set.of("FeignClient");

    public static final Set<String> MAPPING_ANNOTATIONS = Set.of(
            "RequestMapping", "GetMapping", "PostMapping", "PutMapping", "DeleteMapping", "PatchMapping"
    );

    public static boolean hasAny(java.util.List<String> annotations, Set<String> candidates) {
        return annotations.stream().anyMatch(candidates::contains);
    }
}
