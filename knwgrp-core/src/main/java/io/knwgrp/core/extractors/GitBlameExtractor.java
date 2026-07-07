package io.knwgrp.core.extractors;

import io.knwgrp.core.AnalysisContext;
import io.knwgrp.core.Extractor;
import io.knwgrp.model.Node;
import io.knwgrp.model.NodeType;
import io.knwgrp.model.Provenance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Attaches "who last touched this and when" to every class/interface/enum, by running a single
 * {@code git log} over the whole repo and taking, for each file, the author/date of the first
 * (i.e. most recent, since git log is newest-first) commit that mentions it. This is the
 * "ownership" signal that code-health tools like CodeScene/repowise build entire views around —
 * cheap to compute here since it's one process invocation regardless of repo size.
 *
 * <p>Fails soft: a non-git repo, a missing {@code git} binary, or a slow/hanging log (30s cap)
 * all result in this extractor contributing nothing rather than aborting the whole analysis.
 */
public class GitBlameExtractor implements Extractor {

    private static final Logger log = LoggerFactory.getLogger(GitBlameExtractor.class);
    private static final long TIMEOUT_SECONDS = 30;

    @Override
    public String name() {
        return "GitBlameExtractor";
    }

    @Override
    public void extract(AnalysisContext context) {
        if (!Files.isDirectory(context.repoRoot().resolve(".git"))) {
            log.info("Not a git repository — skipping git blame extraction");
            return;
        }

        Map<String, String[]> fileToAuthorDate = runGitLog(context);
        if (fileToAuthorDate.isEmpty()) {
            return;
        }

        for (Node node : context.graph().getNodes()) {
            if (node.getType() != NodeType.CLASS && node.getType() != NodeType.INTERFACE && node.getType() != NodeType.ENUM) {
                continue;
            }
            Provenance prov = node.getProvenance();
            if (prov == null) continue;
            String[] info = fileToAuthorDate.get(prov.filePath());
            if (info != null) {
                node.withAttribute("lastModifiedBy", info[0]);
                node.withAttribute("lastModifiedDate", info[1]);
            }
        }
    }

    private Map<String, String[]> runGitLog(AnalysisContext context) {
        Map<String, String[]> fileToAuthorDate = new HashMap<>();
        try {
            ProcessBuilder pb = new ProcessBuilder("git", "log", "--pretty=format:COMMIT|%an|%ad", "--date=short", "--name-only");
            pb.directory(context.repoRoot().toFile());
            pb.redirectErrorStream(true);
            Process process = pb.start();

            String currentAuthor = null;
            String currentDate = null;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.startsWith("COMMIT|")) {
                        String[] parts = line.split("\\|", 3);
                        currentAuthor = parts.length > 1 ? parts[1] : null;
                        currentDate = parts.length > 2 ? parts[2] : null;
                    } else if (!line.isBlank() && currentAuthor != null) {
                        String filePath = line.trim().replace('\\', '/');
                        fileToAuthorDate.putIfAbsent(filePath, new String[]{currentAuthor, currentDate});
                    }
                }
            }

            boolean finished = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                log.warn("git log did not finish within {}s — blame data may be incomplete", TIMEOUT_SECONDS);
            }
        } catch (IOException | InterruptedException e) {
            log.warn("Failed running git log for blame extraction: {}", e.toString());
            return new HashMap<>();
        }
        return fileToAuthorDate;
    }
}
