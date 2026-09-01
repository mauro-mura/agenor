# ADR-027: Minimal Runtime Core — LLM / Generic-MAS Module Split

**Status**: Accepted
**Date**: 2026-08-03
**Last Modified**: 2026-08-15 (see Amendments below)
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
| `agenor-runtime-llm` | `agent/LLMAgent.java` (renamed on move, see below), `memory/llm/*` (8 classes), `guardrail/*` (7 classes), `reflection/DefaultReflectionStrategy.java` | `agenor-runtime` |
| `agenor-runtime-ext` | `memory/InMemoryStore.java`, `filter/*`, `ratelimit/*`, `condition/*`, `persistence/*`, `behavior/composite/*` (3), `behavior/advanced/*` (9, incl. `HumanCheckpointBehavior`), `hitl/*` (5), `knowledge/*` (2) | `agenor-runtime` |
| `agenor-runtime-scanning` | `discovery/*` (3 classes) | `agenor-runtime` (see Amendment 2026-08-08 — no longer depends on `agenor-runtime-ext`) |

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
- ~~`processAgentAnnotations()` silently no-ops when `agenor-runtime-scanning` is absent,
  even though it runs unconditionally in `start()`.~~ **Superseded by the 2026-08-08 amendment**,
  which made annotation processing independent of `AgentDiscoveryEngine`; see
  *Correction to "Negative / trade-offs"* in the 2026-08-15 amendment for what happens today.
  The silent failure that does remain — an annotation whose processor lives in an absent module —
  is mitigated there by the startup diagnostics originally promised in this bullet.
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

## Amendment (2026-08-08): unconditional annotation processing, `AnnotationProcessor` split

**Status of this amendment**: Accepted, same status as the parent ADR.

### Problem

The original decision (see "Known limitation carried forward") accepted that
`processAgentAnnotations()` silently no-ops when `agenor-runtime-scanning` is absent,
reasoning that annotation processing was inherently tied to the scanning SPI. Reading
the actual `AnnotationProcessor` implementation post-extraction showed this reasoning
was wrong: `@AgenorMessageHandler` processing and five of `@Behavior`'s twelve dispatch
arms (`ONE_SHOT`, `CYCLIC`, `WAKER`, `EVENT_DRIVEN`, `CUSTOM`) use only classes already
in `agenor-runtime` core — `AnnotationProcessor` ended up gated behind the scanning SPI
purely because of its historical package location (`discovery/*`), not because of any
real dependency. Consequence: every manually-registered agent using
`registerAgent(Agent)` — including every example in `agenor-examples`, e.g.
`PingPongExample`, which uses `@Behavior(type=CYCLIC)` and `@AgenorMessageHandler` with
zero classpath scanning — silently lost annotation processing if
`agenor-runtime-scanning` happened to be absent from the classpath, with only a log line
as evidence.

### Decision

1. `dev.agenor.core.spi.AgentDiscoveryEngine` loses its `processAnnotations(Agent)`
   method — it becomes a pure discovery/DI SPI (`initialize`, `scanForAgents`,
   `createAgents`, `createAgent`, `addService`).
2. `AnnotationProcessor` (formerly `agenor-runtime-scanning`) is split by actual
   dependency, the same axis this ADR already used for the module split itself:
   - `dev.agenor.runtime.annotation.AgentAnnotationProcessor` (new, in `agenor-runtime`)
     handles `@AgenorMessageHandler` (all of it) and `@Behavior` for
     `ONE_SHOT`/`CYCLIC`/`WAKER`/`EVENT_DRIVEN`/`CUSTOM`. It has no optional-module
     dependency and needs no `ServiceLoader` indirection for these cases.
   - A new SPI, `dev.agenor.core.spi.BehaviorAnnotationExtension` (in `agenor-core`),
     covers the remaining `@Behavior` types (`CONDITIONAL`, `THROTTLED`, `BATCH`,
     `RETRY`, `SEQUENTIAL`, `PARALLEL`, `FSM`), whose implementation classes live in
     `agenor-runtime-ext`. Implemented by
     `dev.agenor.runtime.behavior.advanced.ExtBehaviorAnnotationExtension` in
     `agenor-runtime-ext`.
3. `AgenorRuntime.processAgentAnnotations()` becomes **unconditional** — it always runs
   for every registered agent during `start()`, regardless of which optional modules are
   present. Unlike every other ADR-027 SPI seam (which silently no-ops when its module is
   absent, because absence is an expected, benign configuration for a scanning-free app),
   using an `agenor-runtime-ext`-only `@Behavior` type without `agenor-runtime-ext`
   present is a hard `IllegalStateException` naming the missing module, and fails
   `start()` in its entirety (not just the offending agent) — thrown out of `start()`.
   Rationale for this asymmetry: a `@Behavior` annotation names its required type
   explicitly and is always known at registration time — there is no "maybe this app
   just doesn't use annotations" ambiguity the way there is for, say, HITL or LLM memory
   management, whose absence is a legitimate steady state. Failing the whole `start()`
   rather than just that agent matches the existing hard-fail contract of
   `createAgent(Class)` without scanning: a missing module is an application-wide
   configuration error, not a per-agent one.
4. `AgenorRuntime.createAgent(Class<T>)`'s annotation-processing step no longer routes
   through `AgentDiscoveryEngine`; it calls the same unconditional
   `AgentAnnotationProcessor` directly.
5. `AgenorRuntime.registerAgent(Class<T>)`, added earlier in this same unreleased
   development cycle as "the scanning-free default for `Class`-based registration," is
   **removed**. Its own javadoc already described it as existing "purely to save the
   caller a manual `new AgentClass()`"; with (1)-(4) above, plain
   `registerAgent(Agent)` plus `@Behavior`/`@AgenorMessageHandler` now works correctly
   with zero optional modules present, which was this method's original and only
   justification. It also introduced a `registerAgent(null)` overload-resolution
   ambiguity against `registerAgent(Agent)`, requiring an explicit cast in one existing
   test — a self-inflicted wart this removal also cleans up.
6. As a side effect of (2), `agenor-runtime-scanning` no longer depends on
   `agenor-runtime-ext` — verified by inspection that `AgentFactory`, `AgentScanner`,
   and `DefaultAgentDiscoveryEngine` (the only files remaining in that module) never
   imported any `agenor-runtime-ext` type directly; the dependency existed solely to
   compile the now-relocated ext-dependent half of `AnnotationProcessor`.

### Consequences

- **Positive**: closes a real bug (annotations silently inert for manually-registered
  agents without the scanning module), simplifies the module graph
  (`agenor-runtime-scanning` sheds a dependency), and removes a redundant, already-buggy
  public method (`registerAgent(Class<T>)`) before it ever shipped.
- **Negative / trade-off**: `@Behavior` types now have two different failure modes for
  "required module absent" depending on type — `AgentDiscoveryEngine`-mediated calls
  (`createAgent(Class)`, `scanPackage`) still fail via `IllegalStateException` naming
  `agenor-runtime-scanning`; `@Behavior`-annotation-mediated ext-type dispatch fails via
  a *different* `IllegalStateException` naming `agenor-runtime-ext`. Both are loud,
  clear failures (no silent no-op in either case) but a developer debugging "why did
  startup throw" needs to read the message to know which module is missing, rather than
  there being one universal "scanning absent" error. Judged acceptable: the messages are
  explicit about which module and why.
- **Neutral**: this amendment does not change the ADR's four-module target shape or
  dependency DAG — `agenor-runtime-scanning`'s `agenor-runtime-ext` edge is removed, which
  is a strict simplification of the previously-decided graph, not a new architectural
  axis. `CIRCUIT_BREAKER`/`SCHEDULED`/`PIPELINE` remain permanently unsupported via
  `@Behavior` (pre-existing gap, unchanged by this amendment — they always fell through
  to `UnsupportedOperationException` regardless of classpath).

### The `Final module set` table (main body of this ADR, above) is updated as follows

`agenor-runtime-scanning`'s `Depends on` column changes from
`agenor-runtime, agenor-runtime-ext (for @Behavior(type=FSM|PARALLEL|SEQUENTIAL) resolution)`
to simply `agenor-runtime`.

## Amendment (2026-08-15): conventions for the SPI seam, and startup diagnostics

Two boundaries this ADR implies but never wrote down, plus the correction of one trade-off
bullet that the 2026-08-08 amendment made obsolete without updating.

### C1 — no new feature-specific accessors on `AgenorRuntime`

`getApprovalService()` is grandfathered. Any further optional capability is reached through its
own SPI or through the module that provides it, never through a new getter on the runtime entry
point.

Rationale: the entry point's public surface would otherwise grow by one method per optional
feature, and it is the surface that gets frozen at 1.0. A getter also re-creates the coupling
this ADR removed — `AgenorRuntime` would have to name the optional type in its own signature.

### C2 — threshold on SPI proliferation

There are **five** core SPIs, not four: `AgentDiscoveryEngine`, `AgentRegistrationExtension`,
`BehaviorAnnotationExtension`, `DefaultLLMMemoryManagerProvider`, `HitlSupportProvider`. The
fifth arrived with the 2026-08-08 amendment above.

All five are accepted as they stand. **Before adding a sixth**, evaluate consolidating them into
a generic lifecycle-hook + capability-registry model, and record the outcome in a new ADR
whichever way it goes — including a decision to keep the feature-named SPIs, which is a
legitimate result and worth writing down once so it is not re-litigated.

Not before: at five, each SPI still names a real, distinct extension point, and a capability
registry would trade a readable `ServiceLoader.load(HitlSupportProvider.class)` for an untyped
lookup that fails at runtime instead of at compile time.

### Correction to "Negative / trade-offs": the annotation-processing bullet

The bullet stating that `processAgentAnnotations()` "silently no-ops when
`agenor-runtime-scanning` is absent" **describes behaviour that no longer exists** — the
2026-08-08 amendment above replaced it and the bullet was never updated. What actually happens
today:

- `@AgenorMessageHandler` and the core `@Behavior` types are processed unconditionally by
  `AgentAnnotationProcessor`, which `agenor-runtime` always constructs. `AgentDiscoveryEngine`
  is not involved, so its absence changes nothing here.
- An ext-only `@Behavior` type without `agenor-runtime-ext` fails `start()` with an
  `IllegalStateException` naming the module. Loud, not silent.
- `AgentDiscoveryEngine`-mediated calls (`createAgent(Class)`, package scanning) still throw
  when `agenor-runtime-scanning` is absent.

That leaves exactly one silent failure mode, and it is a different one from what the bullet
described: an annotation whose only processor lives in an absent module — `@WithGuardrails`
without `agenor-runtime-llm`, `@RequiresApproval` without `agenor-runtime-ext` — is simply never
acted on, and the agent runs without the protection it declares.

### Startup diagnostics — the promised mitigation, now implemented

The original "mitigated with a one-time startup log line" was never built. It now exists, in
`AgenorRuntime.logOptionalModuleDiagnostics()`, called once per `start()`:

- one INFO line naming which optional modules resolved, because `ServiceLoader` finding nothing
  is indistinguishable from finding everything unless the runtime says so;
- one aggregated WARN per annotation type that no loaded extension claims, naming the affected
  agents.

The mechanism is an inversion: `AgentRegistrationExtension` gained a diagnostics-only
`handledAnnotations()` default method, so each module declares what it processes and
`agenor-runtime` never needs a map from annotation to artifact. `agenor-core` contributes only
`OPTIONAL_FEATURE_ANNOTATIONS`, a list of its own annotation types; **no module artifact name
appears in `agenor-core`**, which was the binding constraint.

Two limits worth stating rather than discovering later:

- The WARN detects a *missing module*, not an annotation placed where its processor cannot act.
  `@WithGuardrails` applies to `LLMAgent` subclasses and `@RequiresApproval` to `BaseAgent`
  subclasses; on any other agent they do nothing even with the module present, and the runtime
  cannot know that without the coupling C1 forbids. Each extension therefore reports that case
  itself, in the module that has the knowledge.
- `OPTIONAL_FEATURE_ANNOTATIONS` is a maintained list. An annotation added without an entry
  there degrades exactly as before this amendment. Its Javadoc says so.

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
