# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

Attieniti sempre alle linee guida definite in [CONTRIBUTING.md](./CONTRIBUTING.md).

## Current phase: didactic

Agenor is a learning lab: a public place to work out how agent systems, classic and LLM-based,
are built and how they behave, on local models by default. The README says so, and ADR-025's
2026-11-21 amendment records what it did to versioning: no `1.0.0`, the version stays `0.x`. The
code is what the experiments run on, and what a reader studies, so it has to stay readable at a
tag, one concept at a time. Four rules:

1. **Code serves an experiment or fixes a defect.** New surface is opened by something being built
   that needs it — an experiment, an example, an article — never by an analysis noticing it is
   absent. Supervision is the standing case: no restart policy, no backoff, no escalation, and it
   stays that way until an experiment needs one.
2. **Write for the other transport.** Behaviour verified only against
   `InMemoryMessageDispatcher` is not verified. This defect class has occurred four times; the
   fourth (`f2a3527`) lost on Redis what the in-memory dispatcher dead-lettered.
3. **One list per fact.** `agenor-examples/README.md` is the only list of the learning path and
   its levels; nothing else names a level count. The README links to the articles' author page
   instead of listing them. A list kept by hand in two places is wrong in one of them by the next
   release.
4. **A release when an article needs a tag**, not on a calendar. It may break a public type when
   an experiment needs it; the CHANGELOG marks the entry `BREAKING` with a `Migration:` line, as
   `tools/changelog-budget.sh --check` enforces.

Until November 2026, while Agenor looked for production users, two more rules bound every
release: a public API with no use outside its own test was deprecated rather than documented
(the zero-usage rule), and a release had to move the count of surface scheduled for removal (the
concept budget). Neither binds now. The census that measured them stays as a tool, because it
costs nothing to run and what it shows is part of what the lab teaches.

### The census, as a tool

`bash tools/api-census.sh --check` still runs on its own, in `build.yml` and in
`bash tools/preflight.sh`: it exits non-zero when a declared removal date has passed, was never
declared, or when a `@Deprecated` carries no `forRemoval` at all. Each of those three has let dead
surface survive releases it should not have. A removal date must name a release that can arrive.
`grep -r "forRemoval = true" --include="*.java" */src/main` lists what is scheduled; every entry
names `0.43.0`, so reopening at `0.43.0-SNAPSHOT` turns the check red until that release pays them.

`bash tools/api-census.sh` (it writes to stdout — redirect it) gives per-type verdicts. Three
lessons from reading it, still true:

- **A zero in the usage column is a question about why, not a verdict.** `EmbeddingProvider`
  scored as unused because the one example that needed embeddings had reimplemented it against
  LangChain4j directly. Deprecating on that evidence would have deleted the answer while leaving
  the need.
- **Deleting a page is not progress, and the tool cannot tell you.** The verdict separates
  *plumbing* from *unused* by asking whether user-facing documentation presents the type, so
  removing the page re-scores the type as "plumbing — correctly invisible". After a documentation
  change, re-run it and compare per type with `tools/api-census-diff.sh <before.md> <after.md>`.
- **Count constants and members, not only types.** A user writes `@Behavior(type = CYCLIC)` and
  never names the class behind it, so for an annotation-driven feature the constant is the unit
  of decision.

ADRs in `docs/adr/` are authoritative over these rules where they conflict, and
[CONTRIBUTING.md](./CONTRIBUTING.md) is authoritative over this file.

## Build & Test Commands

`mvn clean install` and `mvn clean test` are in CONTRIBUTING's Development Setup. What is worth
having here are the narrow forms, and the two invocations whose absence is silent:

```bash
# One module, one class, one method
mvn test -pl agenor-core
mvn test -Dtest=AgentQueryTest -pl agenor-core
mvn test -Dtest=AgentQueryTest#shouldCreateQueryByType -pl agenor-core

# Integration tests are opt-in: `mvn clean install` runs none of them and still says BUILD SUCCESS
mvn verify -Dintegration.tests.enabled=true

# Run an example
mvn exec:java -pl agenor-examples -Dexec.mainClass="dev.agenor.examples.PingPongExample"

# The pre-release checklist as a pass/fail table; --fast drops the two Maven rows
bash tools/preflight.sh
```

## Module Architecture

11-module Maven project. `agenor-bom` is the Bill of Materials for version management; use it when adding dependencies.

| Module | Role |
|--------|------|
| `agenor-core` | Pure interfaces and records — no implementations. All contracts live here. |
| `agenor-runtime` | In-memory implementations of core interfaces (`BaseAgent`, messaging, directory, scheduler, dialogue); the default runtime for development. |
| `agenor-runtime-llm` | LLM-aware runtime pieces split out from `agenor-runtime` (ADR-027): `LLMAgent`, LLM memory management, guardrails, reflection strategy. |
| `agenor-runtime-ext` | Extended runtime pieces split out from `agenor-runtime` (ADR-027): `InMemoryStore`, filters, file persistence, composite behaviors, HITL, knowledge. |
| `agenor-runtime-scanning` | Classpath scanning and DI-based agent discovery split out from `agenor-runtime` (ADR-027), isolated for GraalVM native-image friendliness. |
| `agenor-adapters` | LLM providers (OpenAI, Anthropic, Ollama via LangChain4j), MCP adapter, A2A protocol. |
| `agenor-adapters-persistence` | JDBC-backed directory and HITL persistence (ADR-022). |
| `agenor-spring-boot-starter` | Spring Boot 4.0.x auto-configuration for Agenor. |
| `agenor-tools` | Web console (Jetty) and CLI (PicoCLI). |
| `agenor-examples` | Runnable examples organized as a learning path; its README is the only list of the levels and their examples. |

## Core Architecture Patterns

**Interface-First Design**: `agenor-core` defines all contracts as interfaces (`Agent`, `MessageDispatcher`, `AgentDirectory`, `BehaviorScheduler`, `LLMProvider`, `MemoryStore`). Implementations live in other modules and are swappable without user code changes. `MessageDispatcher` composes `TopicPublisher`, `TopicSubscriber`, `DirectMessenger`, `DirectReceiver`; `AgentDirectory` composes `AgentRegistry`, `AgentResolver`, `AgentDiscovery`, `AgentPresence`. `MessageService` was removed at 0.22.0, and the old `dev.agenor.core.AgentDirectory` facade at 0.28.0 (`858b74a`), four releases past its own date — `dev.agenor.core.directory.AgentDirectory` is the one that exists. Both removals leave ghosts in prose and Javadoc that resolve to nothing: check a name against the tree before repeating it. `Condition` was in this list until 0.30.0 deprecated it; 0.32.0 removed it. It was designed as a core contract and never became one, which a list of contracts cannot tell you on its own.

**Annotation-Based Configuration**: Agents and behaviors are discovered via annotations:
- `@Agent` — marks a class as an agent
- `@Behavior` — declares behavior type: ONE_SHOT, CYCLIC, FSM. The other twelve constants were removed in 0.30.0, with the eleven annotation elements that parameterised them; the 0.30.0 changelog names the replacement for each
- `@AgenorMessageHandler` — declares message handlers
- `@Persist` / `@PersistenceConfig` — persistence
- `@DialogueHandler` — dialogue protocol handlers

**Runtime Bootstrap**: `AgenorRuntime` in `agenor-runtime` is the entry point for assembling the agent system. `BaseAgent` is the base class for agents; `LLMAgent` extends it with LLM integration and memory management.

**LLM Memory**: Three context window strategies: `FixedWindow`, `SlidingWindow` (default), `Summarization`. `DefaultLLMMemoryManager` bridges conversation history with token budgets.

**A2A Protocol**: `AgenorA2AAdapter` is a smart router for internal/external agent-to-agent messages; `AgenorA2AClient` handles HTTP/JSON-RPC calls to external agents; `AgenorAgentExecutor` turns an incoming A2A task into a request to an Agenor agent; `AgenorA2AServer` (0.39.0) wraps it with the rest of the SDK so an application serves the agent by mounting two endpoints in any HTTP server.

**LLM Provider Factory**: Use `LLMProviderFactory` (builder pattern) in `agenor-adapters` to instantiate OpenAI, Anthropic, or Ollama providers. A model is named by its string id; the per-provider `Models` enums are deprecated for removal in 0.43.0 (see the census section above), and a context window the fallback does not cover is registered with `ModelTokenLimits.register`.

## Key Conventions

Java style, naming, null-safety, JavaDoc and the architecture principles: CONTRIBUTING § Code
Standards, which is binding rather than advisory. What it does not say:

- `bash tools/coverage-report.sh` prints the per-module line coverage the 80% target is about,
  worst first. Nothing enforces the threshold, by decision — two modules are under it today,
  and a `check` rule would fail the build before anyone decided which of them is worth raising.

## Testing

Categories, frameworks, and the rule that a behaviour verified only against
`InMemoryMessageDispatcher` is not verified: CONTRIBUTING § Testing Guidelines. The trap worth
repeating here, because it cost the project a release: `*IT` classes are opt-in, so a run without
`-Dintegration.tests.enabled=true` skips every one of them while reporting BUILD SUCCESS.
`bash tools/assert-integration-tests-ran.sh` tells the two apart, and `tools/preflight.sh` reports
that state as SKIP rather than PASS.

## Architecture Decision Records

`docs/adr/` holds them and `docs/adr/README.md` is the index — read that rather than a list kept
by hand here, which stopped at ADR-020 while the tree reached ADR-033. Where an ADR and this file
disagree, the ADR wins.

The four a change is most likely to run into: **ADR-002** (interface-first: contracts in
`agenor-core`, implementations elsewhere), **ADR-018** and **ADR-027** (where a new optional
feature goes — an `optional=true` dependency or its own module, and what that costs), and
**ADR-020** (the `MessageDispatcher` / `AgentDirectory` capability split that distributed
backends are built on).

## graphify

This project has a knowledge graph at graphify-out/ with god nodes, community structure, and cross-file relationships.

Rules:
- For codebase questions, first run `graphify query "<question>"` when graphify-out/graph.json exists. Use `graphify path "<A>" "<B>"` for relationships and `graphify explain "<concept>"` for focused concepts. These return a scoped subgraph, usually much smaller than GRAPH_REPORT.md or raw grep output.
- If graphify-out/wiki/index.md exists, use it for broad navigation instead of raw source browsing.
- Read graphify-out/GRAPH_REPORT.md only for broad architecture review or when query/path/explain do not surface enough context.
- After modifying code, run `graphify update .` to keep the graph current (AST-only, no API cost).
