package io.knwgrp.spring;

import io.knwgrp.core.AnalysisContext;
import io.knwgrp.core.Extractor;
import io.knwgrp.model.Node;
import io.knwgrp.model.NodeType;

import java.util.List;

/**
 * Tags each CLASS/INTERFACE node already produced by {@code knwgrp-java} with a
 * {@code springStereotype} attribute (controller/service/repository/component/configuration/
 * entity/feignClient/plain) based on its annotations. This runs after
 * {@code JavaSourceExtractor} and is a prerequisite for {@link EndpointExtractor},
 * {@link DependencyInjectionExtractor}, and the JPA entity extractor, all of which need to
 * know "is this a controller" / "is this an entity" without re-deriving it themselves.
 */
public class StereotypeExtractor implements Extractor {

    @Override
    public String name() {
        return "StereotypeExtractor";
    }

    @Override
    @SuppressWarnings("unchecked")
    public void extract(AnalysisContext context) {
        for (Node node : context.graph().getNodes()) {
            if (node.getType() != NodeType.CLASS && node.getType() != NodeType.INTERFACE) {
                continue;
            }
            Object rawAnnotations = node.getAttribute("annotations");
            if (!(rawAnnotations instanceof List)) {
                continue;
            }
            List<String> annotations = (List<String>) rawAnnotations;
            node.withAttribute("springStereotype", classify(annotations));
        }
    }

    private String classify(List<String> annotations) {
        if (SpringStereotypes.hasAny(annotations, SpringStereotypes.FEIGN_CLIENT)) return "feignClient";
        if (SpringStereotypes.hasAny(annotations, SpringStereotypes.CONTROLLER)) return "controller";
        if (SpringStereotypes.hasAny(annotations, SpringStereotypes.SERVICE)) return "service";
        if (SpringStereotypes.hasAny(annotations, SpringStereotypes.REPOSITORY)) return "repository";
        if (SpringStereotypes.hasAny(annotations, SpringStereotypes.ENTITY)) return "entity";
        if (SpringStereotypes.hasAny(annotations, SpringStereotypes.CONFIGURATION)) return "configuration";
        if (SpringStereotypes.hasAny(annotations, SpringStereotypes.COMPONENT)) return "component";
        return "plain";
    }
}
