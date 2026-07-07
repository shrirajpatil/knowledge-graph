package io.knwgrp.spring;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.*;
import io.knwgrp.core.AnalysisContext;
import io.knwgrp.core.Extractor;
import io.knwgrp.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Detects background work invisible to the REST/messaging graph: {@code @Scheduled} methods
 * (with cron/fixedRate/fixedDelay decoded to a plain-English cadence where the pattern is
 * simple enough to be unambiguous) and {@code @Async} methods. These run on their own without
 * any inbound call, so without this extractor they'd be undiscoverable dead-looking code.
 *
 * <p>Cron decoding is deliberately conservative: only a few common simple shapes (fixed daily
 * time, "every N seconds/minutes") are translated to English. Anything else — including
 * placeholder-driven expressions like {@code ${job.cron}} that can't be resolved without
 * knowing the active profile — is shown as the raw cron string rather than guessed, consistent
 * with the rest of this tool's "never fabricate a fact" stance.
 */
public class ScheduledJobExtractor implements Extractor {

    private static final Logger log = LoggerFactory.getLogger(ScheduledJobExtractor.class);

    @Override
    public String name() {
        return "ScheduledJobExtractor";
    }

    @Override
    public void extract(AnalysisContext context) {
        List<Node> classes = context.graph().getNodes().stream()
                .filter(n -> n.getType() == NodeType.CLASS || n.getType() == NodeType.INTERFACE)
                .toList();

        for (Node classNode : classes) {
            Provenance prov = classNode.getProvenance();
            if (prov == null) continue;
            Path file = context.repoRoot().resolve(prov.filePath());
            if (!Files.exists(file)) continue;

            try {
                processFile(context, file, prov.filePath(), classNode);
            } catch (Exception e) {
                log.warn("Failed extracting scheduled jobs from {}: {}", prov.filePath(), e.toString());
            }
        }
    }

    private void processFile(AnalysisContext context, Path file, String relativePath, Node classNode) throws IOException {
        CompilationUnit cu = StaticJavaParser.parse(file);
        String simpleName = classNode.getName();

        cu.getTypes().stream()
                .filter(t -> t.getNameAsString().equals(simpleName))
                .findFirst()
                .ifPresent(type -> processType(context, type, relativePath, classNode));
    }

    private void processType(AnalysisContext context, TypeDeclaration<?> type, String relativePath, Node classNode) {
        String classFqn = String.valueOf(classNode.getAttribute("fqn"));

        for (MethodDeclaration method : type.getMethods()) {
            boolean isAsync = false;
            for (AnnotationExpr ann : method.getAnnotations()) {
                if (ann.getNameAsString().equals("Scheduled")) {
                    recordScheduledJob(context, classFqn, method, ann, relativePath);
                } else if (ann.getNameAsString().equals("Async")) {
                    isAsync = true;
                }
            }
            if (isAsync) {
                String methodId = NodeIds.forMethod(classFqn, buildSignature(method));
                context.graph().findNode(methodId).ifPresent(n -> n.withAttribute("async", true));
            }
        }
    }

    private void recordScheduledJob(AnalysisContext context, String classFqn, MethodDeclaration method, AnnotationExpr ann, String relativePath) {
        String cron = null, fixedRate = null, fixedDelay = null, initialDelay = null;

        if (ann instanceof NormalAnnotationExpr normal) {
            for (MemberValuePair pair : normal.getPairs()) {
                String value = literalValue(pair.getValue());
                switch (pair.getNameAsString()) {
                    case "cron" -> cron = value;
                    case "fixedRate", "fixedRateString" -> fixedRate = value;
                    case "fixedDelay", "fixedDelayString" -> fixedDelay = value;
                    case "initialDelay", "initialDelayString" -> initialDelay = value;
                    default -> { /* zone not surfaced */ }
                }
            }
        } else if (ann instanceof SingleMemberAnnotationExpr single) {
            cron = literalValue(single.getMemberValue());
        }

        // A String-typed member holding a ${...} placeholder can't be resolved to an actual
        // cadence without knowing the active profile — show the config key it comes from
        // instead of a bare "scheduled", which is still informative without guessing a number.
        String schedule = cron != null ? describeCron(cron)
                : fixedRate != null ? (fixedRate.contains("${") ? "runs at a rate from " + fixedRate : "every " + fixedRate + "ms")
                : fixedDelay != null ? (fixedDelay.contains("${") ? "runs with a delay from " + fixedDelay : "every " + fixedDelay + "ms after the previous run finishes")
                : "scheduled";

        int line = method.getBegin().map(p -> p.line).orElse(-1);
        String methodId = NodeIds.forMethod(classFqn, buildSignature(method));
        String jobId = NodeIds.forScheduledJob(classFqn, method.getNameAsString());

        Node jobNode = new Node(jobId, NodeType.SCHEDULED_JOB, method.getNameAsString())
                .withAttribute("schedule", schedule)
                .withAttribute("cron", cron)
                .withAttribute("fixedRate", fixedRate)
                .withAttribute("fixedDelay", fixedDelay)
                .withAttribute("initialDelay", initialDelay)
                .withProvenance(Provenance.of(relativePath, line, name()));
        context.graph().addNode(jobNode);

        context.graph().addEdge(new Edge(methodId, jobId, EdgeType.SCHEDULED_AS)
                .withProvenance(Provenance.of(relativePath, line, name())));
    }

    private String literalValue(Expression expr) {
        if (expr instanceof StringLiteralExpr s) return s.asString();
        if (expr instanceof IntegerLiteralExpr i) return i.asNumber().toString();
        if (expr instanceof LongLiteralExpr l) return l.asNumber().toString();
        return expr.toString();
    }

    /** Only decodes unambiguous 6-field (sec min hour day month weekday) shapes; anything else,
     *  including {@code ${...}}-driven expressions, is returned unchanged. */
    private String describeCron(String cron) {
        if (cron == null || cron.contains("${")) return cron;
        String[] parts = cron.trim().split("\\s+");
        if (parts.length != 6) return cron;
        String sec = parts[0], min = parts[1], hour = parts[2], day = parts[3], month = parts[4], weekday = parts[5];

        if (isNumber(sec) && isNumber(min) && isNumber(hour) && isWildcard(day) && isWildcard(month) && isWildcard(weekday)) {
            return String.format("daily at %02d:%02d:%02d", Integer.parseInt(hour), Integer.parseInt(min), Integer.parseInt(sec));
        }
        if (sec.equals("0") && min.startsWith("*/") && hour.equals("*") && isWildcard(day) && isWildcard(month) && isWildcard(weekday)) {
            return "every " + min.substring(2) + " minutes";
        }
        if (sec.startsWith("*/") && min.equals("*") && hour.equals("*") && isWildcard(day) && isWildcard(month) && isWildcard(weekday)) {
            return "every " + sec.substring(2) + " seconds";
        }
        return cron;
    }

    private boolean isNumber(String s) {
        return s.matches("\\d+");
    }

    private boolean isWildcard(String s) {
        return s.equals("*") || s.equals("?");
    }

    private String buildSignature(MethodDeclaration method) {
        StringBuilder sb = new StringBuilder(method.getNameAsString()).append("(");
        var params = method.getParameters();
        for (int i = 0; i < params.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append(params.get(i).getTypeAsString());
        }
        return sb.append(")").toString();
    }
}
