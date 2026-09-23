# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

Attieniti sempre alle linee guida definite in [CONTRIBUTING.md](./CONTRIBUTING.md).

## Current phase: adoption

The binding constraint is no longer correctness on the message path. It is that a developer who
is not the maintainer can build something useful and understand what they built. Three rules,
each checkable with a `grep`:

1. **Zero-usage rule.** A public API with no uses outside its own test is to be deprecated, not
   documented. An example that exists only to demonstrate one API is not a use of it. Never add
   a third way to do something when two existing ways are unused.
2. **Write for the other transport.** Behaviour verified only against
   `InMemoryMessageDispatcher` is not verified. This defect class has occurred three times.
3. **Concept budget, measured by what leaves.** The number is
   `grep -r "forRemoval = true" --include="*.java" */src/main | wc -l`: the public surface
   actually scheduled for removal, and it is **0** on `main`. This is not a freeze, and a release
   may add types; but one that ships without moving this number means the census produced a
   document instead of a decision.

### Reading that number

**Zero is not a terminal state.** It means nothing is currently scheduled, not that nothing
should ever be. The next entry earns its place from evidence, the way the paid ones did — not
from a sweep looking for candidates.

**Paying a deprecation lowers the count, and that is correct**: it measures surface still
scheduled, not work done. Do not read a drop as a regression, and do not read a rise as progress
on its own — the release that scheduled thirty-eight removals and the one that paid them are both
doing the job. A release may also remove something without scheduling it first, leaving the
number flat: read a flat zero the way you read a drop, by asking what left the tree rather than
what the counter did.

**Count constants and members, not only types.** A user writes `@Behavior(type = THROTTLED)` and
never names `ThrottledBehavior`, so for an annotation-driven feature the constant is the unit of
decision, and deprecating the class alone retires nothing.

`bash tools/api-census.sh --check` is the companion number: it exits non-zero when a declared
removal date has passed, was never declared, or when a `@Deprecated` carries no `forRemoval` at
all. Each of those three has let dead surface survive releases it should not have. Unlike the
census itself, it runs on its own — in `build.yml` and in `bash tools/preflight.sh`.

### What the census cannot decide

Run `bash tools/api-census.sh` (it writes to stdout — redirect it) for the per-type verdicts.
**documented, unnamed** sits at 95 of 263 types across all nine modules that contain code — read
it as a diagnostic, not an agenda. **dead surface** is at **0**.

Applied to the types that differentiate Agenor from an LLM wrapper, both verdicts the census can
hand out — stop presenting it as surface, or deprecate it — would have retired the
differentiator. That is why the census stopped driving releases, and why the work that followed
came from a different question: what a developer cannot currently do. A conversation and a
commitment are now legible on both transports, and a failed message has somewhere to go on every
transport.

**A zero in the usage column is a question about why, not a verdict.** `EmbeddingProvider` scored
as unused surface for the opposite of the obvious reason: the one example that needed embeddings
had reimplemented it against LangChain4j directly rather than call it. Deprecating on that
evidence would have deleted the answer while leaving the need.

**One way to move the number is not progress, and the tool cannot tell you.** The verdict
separates *plumbing* from *unused* by asking whether user-facing documentation presents the type
as surface — so deleting the page that documents a type silences the question instead of
answering it, and re-scores it as "plumbing — correctly invisible", which carries no action. That
has happened, to three types at once. After a documentation change, re-run the census and read
what moved — `tools/api-census-diff.sh <before.md> <after.md>` compares per type rather than by
eye. A verdict that improved because a page went away has not improved, and what the type still
lacks has to be written down somewhere the tool will not stop asking.

### Where the gap is now

**What is left is supervision, and it has no trigger.** No restart policy, no backoff, no
escalation — and nothing a developer is building has asked for one, which is the bar for starting
a gap rather than recording it: a gap is opened by something being built that needs it, never by
an analysis noticing it is absent.

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
| `agenor-examples` | Runnable examples organized as a 6-level learning path (Level 0–5). |

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

**A2A Protocol**: `AgenorA2AAdapter` is a smart router for internal/external agent-to-agent messages; `AgenorA2AClient` handles HTTP/JSON-RPC calls to external agents; `AgenorAgentExecutor` exposes a Agenor agent as an A2A server.

**LLM Provider Factory**: Use `LLMProviderFactory` (builder pattern) in `agenor-adapters` to instantiate OpenAI, Anthropic, or Ollama providers. Provider-specific model variants are defined as enums, not static maps.

## Key Conventions

Java style, naming, null-safety, JavaDoc and the architecture principles: CONTRIBUTING § Code
Standards, which is binding rather than advisory. What it does not say:

- `bash tools/coverage-report.sh` prints the per-module line coverage the 80% target is about,
  worst first. Nothing enforces the threshold, by decision — four modules are under it today,
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
