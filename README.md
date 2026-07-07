# Cartograph

**An AI-free static knowledge graph generator for Java Spring Boot microservices.**

Cartograph reads a Spring Boot service's source code and configuration — no running app, no AI, no guessing — and builds a complete, explorable map of it: every controller, service, repository, entity, endpoint, external dependency, Kafka topic, scheduled job, and config key, plus how they all connect. Every fact it reports traces back to a real file and line.

Point it at a repo, get back a single self-contained HTML file with a live, interactive explorer.

```bash
java -jar cartograph.jar analyze /path/to/your-service -o output
open output/explorer.html
```

---

## Why

Understanding a Spring Boot microservice you didn't write usually means grepping through controllers, chasing `@Autowired` chains, and hoping the README is up to date. Cartograph builds that understanding automatically, straight from the source of truth (the code itself), and keeps it honest — if it can't verify a fact, it says so instead of guessing.

## Features

### Static analysis (no AI, no LLM, no guessing)
- **Spring semantics** — controllers, services, repositories, components, and configuration classes classified from their annotations
- **REST endpoints** — full path/verb resolution (class + method `@RequestMapping` merging), path/query params, request body and response types
- **Dependency injection graph** — constructor and field injection, resolved into a real class-to-class dependency graph
- **JPA entities** — entity → table mapping, `@OneToMany`/`@ManyToOne`/etc. relationships
- **External dependencies** — Feign clients, `RestTemplate`/`WebClient`/`OkHttpClient` calls, Kafka producers/consumers, Redis usage — all with config placeholders resolved to real values where possible
- **Scheduled jobs** — `@Scheduled` methods with cron/fixedRate/fixedDelay decoded to plain English where unambiguous, `@Async` methods flagged
- **Security posture** — `@PreAuthorize`/`@Secured`/`@RolesAllowed`/`@PermitAll` detected per endpoint, with unprotected ones flagged
- **Config usage** — `@Value("${...}")` fields/params linked back to the actual config key they read
- **Git blame** — last-modified-by/date attached to every class from real git history

### Architecture Insights (the things a senior reviewer checks for)
- Circular dependencies between services/repositories/components
- Layering violations (controllers injecting repositories directly)
- JPA entities leaked as API responses
- Unreferenced (dead code) candidates
- Most-coupled classes (refactoring candidates)
- Unprotected endpoints

### Interactive explorer (single self-contained HTML file)
- **Graph** — a live, force-directed, continuously animated map of the whole system. Pan, zoom, drag nodes, click to inspect, blast-radius/impact analysis (upstream vs. downstream), color-by-package or color-by-role
- **Dashboard** — a plain-English summary of what the service actually does, read first before any diagram; architecture health at a glance
- **API Reference** — a Swagger-like page per endpoint: real JSON request/response examples generated from actual DTO fields, "Copy as cURL," and a one-click "Download OpenAPI spec" export
- **Explorer** — a searchable list of literally everything: every class, method, field, config key, table
- **Command palette** (`Ctrl K`) — jump to anything by name, with pinned and recently-viewed nodes surfaced first
- **First-visit onboarding** walkthrough explaining what each tab is for

## Screenshots

<!--
  Add screenshots to docs/screenshots/ using these filenames, then this section renders them:
-->

| Dashboard | Graph |
|---|---|
| ![Dashboard](docs/screenshots/dashboard.png) | ![Graph](docs/screenshots/graph.png) |

| API Reference | Explorer |
|---|---|
| ![API Reference](docs/screenshots/api-reference.png) | ![Explorer](docs/screenshots/explorer.png) |

## Getting started

**Requirements:** Java 21+, Maven 3.9+

```bash
git clone https://github.com/shrirajpatil/knowledge-graph.git
cd knowledge-graph
mvn clean install

java -jar knwgrp-cli/target/cartograph.jar analyze /path/to/your-spring-boot-service -o my-output
```

Open `my-output/explorer.html` in any browser. That's it — no server, no config, nothing installed on the target repo.

### Other outputs
Each run also writes:
- `graph.json` — the canonical graph (nodes/edges/provenance) for scripting or other tools
- `component-diagram.mmd` / `entity-diagram.mmd` — Mermaid diagrams

## Project structure

```
knwgrp-model    graph node/edge types, provenance, JSON schema
knwgrp-core     pipeline engine, extractor SPI, config (YAML/properties) parsing
knwgrp-java     JavaParser-based AST extraction (classes, methods, packages)
knwgrp-spring   Spring semantic layer — stereotypes, endpoints, DI, JPA, Feign,
                Kafka, RestTemplate/WebClient, Redis, scheduled jobs, config usage
knwgrp-export   JSON, Mermaid, and the HTML explorer template
knwgrp-cli      `cartograph analyze` command
samples         a small fixture Spring Boot service used to verify the pipeline
```

## How it works

```
Source repo
   │
   ├─ Config parser        (application*.yml/.properties → flat key/value model)
   ├─ AST extractor         (JavaParser → classes, methods, fields, javadoc)
   ├─ Git blame extractor   (one `git log` → last-modified-by/date per class)
   ├─ Spring semantic layer (annotations → stereotypes, DI graph, endpoints, JPA,
   │                         Feign/Kafka/REST-client/Redis, scheduled jobs, config usage)
   │
   ▼
Canonical graph  →  JSON / Mermaid / self-contained HTML explorer
```

Every extractor is tolerant of partial information — a file that fails to parse doesn't abort the run, and every fact carries a `Provenance` (file, line, extractor) so it can be verified independently.
