# ADR-018: Optional Adapter Dependencies Pattern

**Status**: Accepted  
**Date**: 2026-04-23  
**Authors**: Agenor Team  

## Context

As Agenor adds distributed-backend adapters (OpenTelemetry, Redis/Valkey messaging, JDBC
directory, Kafka, Consul, pgvector, …) the question of *where* each adapter lives and *how* its
dependencies reach the consumer recurs with every new backend. Without an explicit rule, each
adapter reopens the same discussion and risks polluting the transitive classpath of consumers
who never asked for that infrastructure.

Three placement strategies exist in the current codebase:

1. **Compile-scope dep inside `agenor-adapters`** — used by LangChain4j, the A2A SDK, and the
   MCP SDK. The library is always on the classpath when `agenor-adapters` is declared.
2. **`optional=true` dep inside `agenor-adapters`** — declared in the Spring Boot starter
   (ADR-016) for Spring Boot itself. The library is available at compile time but absent from
   the transitive classpath; consumers must declare it explicitly if they want it.
3. **Dedicated Maven sub-module** — not yet used; introduced conceptually in ADR-003 as a
   possible future extension point.

Without a decision table, each new adapter produces ad-hoc arguments and inconsistent outcomes.
The consequence is a bloated default classpath (heavy infrastructure jars pulled in silently),
or alternatively, unnecessary module proliferation for lightweight integrations.

Forces in tension:

- **Classpath hygiene vs. DX**: `optional=true` keeps the transitive classpath clean but
  requires the consumer to declare a second dependency; a compile-scope dep is zero-friction
  but leaks transitively.
- **Module granularity vs. build complexity**: a dedicated sub-module is the cleanest isolation
  but adds a POM, a BOM entry, and a separate `README`.
- **Compile-time discoverability vs. runtime safety**: an `optional=true` library present at
  compile time enables `@ConditionalOnClass` checks at runtime; a sub-module approach requires
  the consumer to know the artifact name up-front.

## Decision

Classify every new adapter according to the table below and place it accordingly. No exceptions
without a new or updated ADR.

| Condition | Placement |
|-----------|-----------|
| Adapter uses only lightweight libraries already common in Java services (`java.net.http`, small JSON libs, SLF4J) | `agenor-adapters`, regular `compile` scope |
| Adapter pulls a heavy but generally useful library (OTel SDK, Lettuce/Redis client) that multiple consumers may want independently | `agenor-adapters`, `<optional>true</optional>` |
| Adapter pulls heavyweight *infrastructure* with mutually exclusive alternatives (JDBC + Flyway + connection pool, Consul client, etcd client) or that requires schema/migration management | Dedicated Maven sub-module `agenor-adapters-<concern>` |
| Adapter is commercial / Enterprise-tier | Separate Enterprise module, not in OSS BOM |

**Contract for `optional=true` deps**:

- The dependency is declared in `agenor-adapters/pom.xml` with `<optional>true</optional>`.
- All classes that import the optional library are confined to a dedicated sub-package
  (e.g. `dev.agenor.adapters.telemetry`, `dev.agenor.adapters.messaging.redis`).
- The `agenor-runtime` module must **never** import these classes — it depends only on
  interfaces in `agenor-core`.
- The Spring Boot starter guards auto-configured beans with `@ConditionalOnClass(OptionalLib.class)`.
- CI includes a *classpath isolation test*: a minimal consumer that declares only
  `agenor-adapters` (without the optional library) must compile and run without
  `ClassNotFoundException`.

**Contract for dedicated sub-modules**:

- Module name follows the pattern `agenor-adapters-<concern>` (e.g. `agenor-adapters-persistence`).
- Added to the parent POM `<modules>` list and to `agenor-bom`.
- Depends on `agenor-core` (compile scope) and `agenor-runtime` (provided/test scope only).
- The Spring Boot starter references the sub-module with `<optional>true</optional>` — the
  same classpath isolation guarantee holds.
- The sub-module ships its own `README.md` explaining its scope, and why it is separate from
  `agenor-adapters`.

## Rationale

### Pros

- Single reference point: future adapters (Kafka, Consul, NATS, pgvector, …) can be classified
  without re-opening the design discussion.
- Lightweight consumers (`agenor-adapters` without optional libs) pay zero classpath cost for
  infrastructure they don't use.
- `@ConditionalOnClass` activation in the Spring Boot starter works correctly for both
  `optional=true` and sub-module strategies — the class is either present or absent at runtime.
- Module tree in the BOM makes opt-in decisions visible and auditable.

### Cons

- `optional=true` deps require consumers to declare a second Maven coordinate; not all build
  tools handle optional deps identically (Gradle treats them as implementation deps by default).
- Sub-modules increase the POM file count and BOM surface area.

### Alternatives Considered

- **Always use `optional=true`**: rejected — Flyway schema files and HikariCP startup hooks
  cannot be made truly optional; they run at class-load time, polluting even consumers that
  never exercise the backend.
- **Always use sub-modules**: rejected — unnecessary for a single lightweight library (OTel
  API, Lettuce) that has no startup side-effects and no schema management needs.
- **Shadow / relocate heavy deps**: rejected — relocation breaks `@ConditionalOnClass` guards
  and is a maintenance burden.

## Implementation

Applied from `0.19.0` onward. The table below shows planned applications; each row will be
realised when the corresponding item is implemented:

| Planned release | Adapter | Strategy |
|-----------------|---------|----------|
| 0.19.0 | OpenTelemetry SDK | `optional=true` in `agenor-adapters` |
| 0.21.0 | Lettuce (Redis/Valkey) | `optional=true` in `agenor-adapters` |
| 0.22.0 | HikariCP + Flyway + JDBC drivers | Dedicated `agenor-adapters-persistence` sub-module |
| 0.22.0 | JDBC HITL queue | Same `agenor-adapters-persistence` sub-module (extended) |

Example `pom.xml` snippet for an `optional=true` dep:

```xml
<!-- agenor-adapters/pom.xml -->
<dependency>
    <groupId>io.opentelemetry</groupId>
    <artifactId>opentelemetry-sdk</artifactId>
    <version>${opentelemetry.version}</version>
    <optional>true</optional>
</dependency>
```

Example consumer opt-in (OpenTelemetry):

```xml
<!-- Consumer POM — must declare the optional lib explicitly -->
<dependency>
    <groupId>dev.agenor</groupId>
    <artifactId>agenor-adapters</artifactId>
    <version>${agenor.version}</version>
</dependency>
<dependency>
    <groupId>io.opentelemetry</groupId>
    <artifactId>opentelemetry-sdk</artifactId>
    <version>${opentelemetry.version}</version>
</dependency>
```

Example consumer opt-in (JDBC persistence):

```xml
<!-- Consumer POM — declares the sub-module artifact directly -->
<dependency>
    <groupId>dev.agenor</groupId>
    <artifactId>agenor-adapters-persistence</artifactId>
    <version>${agenor.version}</version>
</dependency>
```

## Consequences

### Positive

- Default `agenor-adapters` classpath stays agent-focused: LLM providers, MCP, A2A — no
  database drivers, no telemetry SDK, no Redis client unless explicitly requested.
- Every new distributed backend has a clear, pre-decided home; architectural reviews can focus
  on design rather than placement.
- CI classpath isolation tests catch accidental leaks before they reach consumers.

### Negative

- Contributors must consult this ADR before adding a new backend dependency.
- Gradle users who expect transitive optional deps to be excluded need explicit action
  (Gradle's `implementation` scope does not skip optional deps by default — documented in
  `agenor-adapters/README.md`).

### Neutral

- `agenor-bom` grows by one entry per new sub-module; at the projected pace (one module per
  minor release) this is manageable.

## Compliance

- Maven Enforcer rule (already present) verifies that `agenor-core` and `agenor-runtime` have
  no transitive deps outside the approved set.
- A new CI step (`classpath-isolation` matrix job) runs a minimal consumer project against each
  `optional=true` adapter to confirm `ClassNotFoundException` does not occur at runtime when the
  optional lib is absent.
- ADR-003 (Maven Multi-Module Structure) is updated to reference this ADR and to include future
  sub-modules in its module table.

## Notes

- Related ADRs: ADR-002 (interface-first architecture), ADR-003 (Maven multi-module structure),
  ADR-004 (progressive complexity), ADR-016 (Spring Boot starter — first use of `optional=true`
  in this project).
- Future ADRs for each planned adapter (OpenTelemetry, Redis messaging,
  `agenor-adapters-persistence`) will reference this ADR as the governing rule.

---

## Amendment — 2026-09-19: there is no Enterprise tier, and that row never had a case

**Supersedes**: the fourth row of the placement table in *Decision* — *"Adapter is commercial /
Enterprise-tier → Separate Enterprise module, not in OSS BOM"*. The row stays above as the record
of what was decided; this says what it turned out to mean.

### Context

The row assumed a commercial tier existed, or would. Written down here once, the assumption
spread: several later ADRs and one user-facing page sent deferred work "to the Enterprise tier",
and one of them went as far as naming its module (`agenor-enterprise-distributed`). None of that
was ever decided anywhere. No such tier, module or roadmap entry exists.

For a reader the difference is not cosmetic. *"Deferred to the Enterprise tier"* says **this will
exist, just not here** — a claim about a future the project has not committed to and has no plan
to deliver. *"Out of scope"* says what is true: it is not being built, and nothing is promised.

### Decision

**The Enterprise tier is out of scope.** Every deferral that pointed at it now reads *out of
scope* — in `docs/hitl-persistence.md`, in the ADR index, and in ADR-020, ADR-021, ADR-022,
ADR-023 and ADR-024. The paired phrasings went with it: an "OSS tier" is just the project, so a
decision "rejected for the OSS tier" is simply rejected.

The fourth row above is therefore inert. It is kept rather than deleted because it is the record
of a real decision, and because the placement question it answers could return if a closed-source
adapter ever appears — at which point it is a new decision with evidence, not an inherited
assumption.

### Consequences

- Nothing in the code changes. This is the same correction applied to the README, to CONTRIBUTING
  and to the LLM guide in this release: removing a claim the project does not support.
- Work that was "deferred to Enterprise" — Kafka transport, event-sourced HITL replay,
  multi-endpoint resolution, a dedicated presence backend — is not thereby scheduled. It is
  unscheduled and now says so.
