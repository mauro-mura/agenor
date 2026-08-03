# ADR-027: Minimal Runtime Core — LLM / Generic-MAS Module Split

**Status**: Accepted
**Date**: 2026-08-03
**Authors**: Project Team
**References**: ADR-002 (Interface-First Architecture), ADR-004 (Progressive Complexity
Strategy), ADR-018 (Optional Adapter Dependencies Pattern), ADR-020 (Core API Refactor)

---

## Context

`agenor-runtime` is a single monolithic module. Any consumer who wants `BaseAgent`,
messaging, or the dialogue subsystem also pulls in `LLMAgent`, guardrails, LLM memory
management, HITL, and package-scanning — regardless of whether they use an LLM at all.
This contradicts ADR-004's progressive-complexity principle (swap implementations without
forcing unrelated dependencies) and makes a pure multi-agent-system (no LLM) deployment
carry dead weight.

A **mechanical** split — one new module per existing package, 7 to 9 modules total — was
considered first. That approach fragments the project without a principled axis, and
surfaces two circular dependencies: `hitl` ↔ `HumanCheckpointBehavior` (in
`behavior.advanced`), and `agent.LLMAgent` ↔ `guardrail`. Resolving those cycles the
conventional way (interface extraction) would add an abstraction layer purely to satisfy
a module boundary that isn't itself well-motivated.

The axis that actually matters is **LLM coupling**, not the package boundaries as they
happen to exist today. Verified by import analysis against commit `d8de56c` — every
package under `agenor-runtime` checked for imports of `core.llm.*` or LangChain4j types,
not by package name — the result is a 4-module split where each of the two circular
dependencies lands on both sides in the *same* module. The cycle becomes an ordinary
intra-module package reference, not a Maven module cycle, and no interface extraction is
needed to break it.

## Decision

### Final module set

| Module | Contents | Depends on |
|---|---|---|
| `agenor-runtime` (core) | Agent lifecycle (`BaseAgent`), messaging, directory, scheduler, dialogue (negotiation protocols incl. Contract-Net), base behaviors (`BaseBehavior`, `CyclicBehavior`, `EventDrivenBehavior`, `OneShotBehavior`, `WakerBehavior`, `ReflectionBehavior`), plus six additional packages with zero LLM/ext/scanning coupling: `config`, `directory`, `lifecycle`, `messaging`, `scheduler`, `telemetry` | `agenor-core` |
| `agenor-runtime-llm` | `agent/LLMAgent.java` (renamed on move, see below), `memory/llm/*` (7 classes), `guardrail/*` (6 classes), `reflection/DefaultReflectionStrategy.java` | `agenor-runtime` |
| `agenor-runtime-ext` | `memory/InMemoryStore.java`, `filter/*`, `ratelimit/*`, `condition/*`, `persistence/*`, `behavior/composite/*` (3), `behavior/advanced/*` (9, incl. `HumanCheckpointBehavior`), `hitl/*` (5), `knowledge/*` (2) | `agenor-runtime` |
| `agenor-runtime-scanning` | `discovery/*` (3 classes) | `agenor-runtime`, `agenor-runtime-ext` (for `@Behavior(type=FSM\|PARALLEL\|SEQUENTIAL)` resolution) |

Dependency graph is a flat DAG: `agenor-core → agenor-runtime → {llm, ext} → scanning (→
ext only)`. No cross-dependency between `llm` and `ext`. A pure-MAS consumer adds zero
LLM dependencies; an LLM consumer adds exactly one module, not four.

### Package-rename note

No `module-info.java` exists in the repository, so split packages across JARs are
tolerated by the classic classpath, but are still avoided here as bad practice. Only one
package actually splits: `dev.agenor.runtime.agent` (`BaseAgent` stays in core, `LLMAgent`
moves out). Every other moved package moves wholesale, so it doesn't split. `LLMAgent`
is renamed on move: `dev.agenor.runtime.agent.LLMAgent` →
`dev.agenor.runtime.llm.LLMAgent`. This is a **breaking change**, acceptable pre-1.0.

### The seam: how core stays compilable without the other three modules

Before any physical extraction, `AgenorRuntime` (the sole entry point, staying in core)
hard-imported classes from all three packages being split out — `agent.LLMAgent`,
`guardrail.GuardrailAnnotationProcessor`, `hitl.{ApprovalService,HitlAnnotationProcessor,
InMemoryApprovalGate}`, `discovery.{AgentFactory,AgentScanner,AnnotationProcessor}`,
`memory.llm.DefaultLLMMemoryManager` — alongside its core-only imports (`agent.BaseAgent`,
`config.DefaultConfigurationLoader`, `directory.*`, `lifecycle.*`,
`messaging.InMemoryMessageDispatcher`, `scheduler.SimpleBehaviorScheduler`). Extracting
the modules without first removing that coupling would simply move the compile error from
"doesn't exist" to "circular module dependency."

The fix is a small `ServiceLoader`-based SPI, entirely in `agenor-core`:

- `dev.agenor.core.spi.AgentRegistrationExtension` — `onAgentRegistered(Agent, RegistrationContext)`,
  invoked once per `registerAgent()` call. Replaces the `instanceof LLMAgent` guardrail/telemetry
  block and the `HitlAnnotationProcessor.process(...)` call with a loop over whatever
  extensions are present on the classpath. Absent `-llm`/`-ext`: the corresponding
  extension simply never registers, so `@WithGuardrails`/`@RequiresApproval` become inert
  annotations rather than compile errors.
- `dev.agenor.core.spi.AgentDiscoveryEngine` — collapses the three discovery fields
  (`AgentScanner`, `AgentFactory`, `AnnotationProcessor`) into one optional collaborator.
  Package-scanning calls (`discoverAndCreateAgents()`, `createAgent(Class)`) fail loudly
  with `IllegalStateException` when absent, since the caller explicitly asked for
  scanning; annotation processing during `start()` (which runs for every agent, including
  manually registered ones) logs once and no-ops instead, so a scanning-free app can still
  call `start()`.
- `dev.agenor.core.spi.HitlSupportProvider` plus a new `dev.agenor.core.hitl.ApprovalHandle`
  interface (and `NoopApprovalHandle` default) replace the concrete `ApprovalService` as
  the runtime's HITL entry point.
- `dev.agenor.core.spi.DefaultLLMMemoryManagerProvider` supplies the default
  `LLMMemoryManager` factory backing `LLMMemoryAware` agents — the one point that was
  already mostly decoupled via the pre-existing `LLMMemoryAware` core interface; this only
  replaces its default implementation's source.

Each optional module registers its implementation via `META-INF/services`, discovered
once in `AgenorRuntime`'s constructor. This reuses a standard JDK mechanism rather than
inventing a bespoke plugin system, matching ADR-018's precedent of `optional=true`
dependencies plus runtime detection for adapter modules.

**Breaking change**: `AgenorRuntime.getApprovalService()`'s return type changes from the
concrete `ApprovalService` class to the new core `ApprovalHandle` interface. The method
name is unchanged to minimize churn; callers that only invoke interface methods
(`approve`/`reject`/`modify`/`submit`/`getPendingRequests`) are unaffected at the source
level but must recompile.

### Options considered

1. **Mechanical one-module-per-package split**. Rejected: no principled axis, produces
   two circular dependencies that would require unrelated interface extraction to fix,
   and fragments the project into 7–9 modules for no consumer-facing benefit.
2. **Interface-extraction surgery** to break the `hitl`/`HumanCheckpointBehavior` and
   `LLMAgent`/`guardrail` cycles directly, keeping the mechanical split otherwise.
   Rejected: solves a problem (the cycles) that the LLM-coupling re-classification below
   makes disappear as a side effect, at lower cost.
3. **LLM/Generic-MAS split via import analysis** (chosen). Both cycles land on both
   sides in the same module by construction; four modules instead of seven to nine; the
   axis matches what consumers actually care about (do I need LLM support or not).
4. For the seam itself — **ad hoc reflection** (`Class.forName` checks scattered through
   `AgenorRuntime`). Rejected: no compile-time contract, harder to test (nothing to mock
   against), and undocumented extension points. **`ServiceLoader` SPI with core-level
   interfaces** (chosen): standard mechanism, testable by registering fakes via
   `META-INF/services` on a test classpath, and the extension points are visible,
   documented types in `agenor-core`.

## Consequences

### Positive

- A pure-MAS consumer depends only on `agenor-core` + `agenor-runtime`, with zero LLM
  transitive dependencies.
- An LLM consumer adds exactly one module (`agenor-runtime-llm`), not four.
- Both circular dependencies surfaced by the initial mechanical-split attempt are
  resolved as a side effect of the reclassification, with no interface-extraction surgery.
- The `ServiceLoader` SPI introduced for this split (`AgentRegistrationExtension`,
  `AgentDiscoveryEngine`, `HitlSupportProvider`, `DefaultLLMMemoryManagerProvider`) is a
  reusable pattern for any future optional integration, not a one-off hack.

### Negative / trade-offs

- Two breaking API changes: the `LLMAgent` package rename and `getApprovalService()`'s
  return-type change. Both acceptable pre-1.0 per project convention (see ADR-025).
- `processAgentAnnotations()` silently no-ops when `agenor-runtime-scanning` is absent,
  even though it runs unconditionally in `start()`. A user relying on `@AgenorMessageHandler`
  or `@Behavior` annotation processing without realizing the module is required would see
  annotations quietly ignored rather than a startup failure. Mitigated with a one-time
  startup log line and explicit javadoc; a hard failure was rejected because it would
  break `start()` for scanning-free apps that have zero annotated agents.
- The SPI adds one layer of indirection (`ServiceLoader` lookup, interface dispatch)
  around collaborators that were previously constructed directly. Negligible at runtime
  (resolved once at startup, not per-call) but adds a few new types to read when tracing
  `registerAgent()`'s behavior.

### Neutral

- `agenor-runtime-scanning`'s dependency on `agenor-runtime-ext` (for
  `@Behavior(type=FSM|PARALLEL|SEQUENTIAL)` resolution in `AnnotationProcessor`) remains
  an ordinary Maven module dependency, not a cycle — this is accepted coupling for this
  split and is unchanged by the SPI work.

## Known limitation carried forward

This ADR documents and begins implementing the full 4-module target, but this pass only
covers writing the decision record and decoupling `AgenorRuntime` via the SPI described
above (task "1.5" in the working plan). The physical module extraction
(`agenor-runtime-llm`/`-ext`/`-scanning` as separate Maven modules), the `agenor-bom`
updates, the `agenor-examples` migration (roughly 87 files reference the packages being
split), the migration guide, and the version bump/release are deferred to follow-up work
tracked in the working plan document, following the same phased-implementation pattern
ADR-026 used for its own deferred `AgenorAgentExecutor` fix.

## Related ADRs

- ADR-002 (Interface-First Architecture): the SPI introduced here is a direct application
  of that principle to optional runtime collaborators.
- ADR-004 (Progressive Complexity Strategy): this split is what makes "swap in-memory →
  enterprise without forcing unrelated dependencies" actually true for LLM support.
- ADR-018 (Optional Adapter Dependencies Pattern): precedent for `optional=true` /
  runtime-detected integration; this ADR applies the same spirit via `ServiceLoader`
  instead of classpath-presence checks.
- ADR-020 (Core API Refactor): precedent for splitting a facade by capability rather than
  by implementation convenience.
