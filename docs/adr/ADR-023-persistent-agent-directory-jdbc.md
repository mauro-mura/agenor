# ADR-023: Persistent Agent Directory with JDBC

**Status**: Accepted  
**Date**: 2026-05-17  
**Authors**: Project Team  
**References**: ADR-001 (Virtual Threads), ADR-002 (Interface-First Architecture),
ADR-004 (Progressive Complexity), ADR-019 (OpenTelemetry Instrumentation),
ADR-020 (Core API Refactor for Distributed Backends), ADR-022 (`jentic-adapters-persistence` Module Split)

---

## Context

`InMemoryAgentDirectory` is JVM-local: agent registrations are lost on restart and invisible
to other nodes. Any multi-node deployment — even before introducing clustering — requires
directory state that survives restarts and is shared across instances.

The Core API refactor (ADR-020) split `AgentDirectory` into four capability interfaces:
`AgentRegistry`, `AgentResolver`, `AgentDiscovery`, and `AgentPresence`. This split is the
enabling precondition for this ADR: a relational backend can implement the three read/write
interfaces that map naturally to SQL, while leaving `AgentPresence` (heartbeat/liveness) to
a backend better suited to high-frequency writes.

---

## Decision

### Capability scope

The JDBC implementation covers three of the four capabilities defined in ADR-020:

| Interface | Implemented | Rationale |
|-----------|-------------|-----------|
| `AgentRegistry` | Yes | upsert + `DELETE` on `jentic_agents` — low write frequency; see Registration semantics |
| `AgentDiscovery` | Yes | `SELECT` by capability, type, status — well-suited to relational indexes |
| `AgentResolver` | Yes | Single-row `SELECT` by `agent_id` — hot path, covered by PK index |
| `AgentPresence` | **No** | See below |

### Why `AgentPresence` is not implemented

`AgentPresence.heartbeat` is called by every agent on a regular interval (default: every
10 seconds). At 100 agents this is 10 writes/second to a single table; at 1 000 agents it is
100 writes/second. Postgres is not optimised for this pattern: each `UPDATE` acquires a row
lock, generates WAL, and triggers autovacuum churn on the updated rows.

The right tool for liveness is a TTL-based store (Redis key expiry, Consul health checks) or
an in-process heartbeat map. The runtime assembles a mixed backend: JDBC for the three
read/write capabilities, in-memory `AgentPresence` for single-node liveness, or a dedicated
presence backend (deferred to Enterprise) for multi-node liveness. The capability split from
ADR-020 makes this mix-and-match possible without any compromise in either implementation.

Attempting to configure `jentic.directory.presence: jdbc` yields a clear startup error
(`UnsupportedCapabilityException`) rather than silent degradation.

### Data access strategy: pure JDBC

No JPA, no Spring Data, no jOOQ. The module uses `PreparedStatement` directly via a thin
`JdbcHelper` utility class.

Rationale:

- **Spring Data JDBC / JPA**: would pull Spring Data Commons and Spring Context as hard
  compile dependencies, tying a non-Spring module to the Spring ecosystem. The module must
  be usable outside Spring (standalone, other DI frameworks). Spring's `DataSource` autowiring
  is supported at the Spring Boot starter level without requiring Spring Data in the module
  itself.
- **jOOQ**: introduces a code-generation step and a significant compile-time dependency for a
  query surface that is small and stable. `PreparedStatement` covers all required queries
  without a build-time DSL. Removing the code-gen step reduces contributor setup friction.
- **Pure JDBC**: portable across all JDBC-compatible databases, no annotation processing,
  no runtime proxies, predictable SQL visible in the source.

All queries use `PreparedStatement`; no SQL string concatenation is used anywhere in the
module (verified in code review and enforced by a SpotBugs rule).

---

## Schema Migration Tool

### Decision: Flyway

Flyway manages schema versioning for `jentic-adapters-persistence`.

### Candidates evaluated

#### Flyway

Flyway applies versioned SQL files (`V1__description.sql`, `V2__description.sql`) in
order, tracking execution in `flyway_schema_history`.

| Property | Flyway |
|----------|--------|
| Migration format | Plain SQL (or Java callbacks) |
| Rollback | Not supported in OSS tier |
| Mental model | Sequential versioned files |
| Dependency footprint | Light (`flyway-core` only) |
| Spring Boot integration | First-class (`spring.flyway.*`) |
| Multi-database support | Yes (dialect-aware DDL) |
| Barrier to entry | Low — plain SQL, no DSL |

#### Liquibase

Liquibase uses changesets identified by `id` + `author`, expressed in XML, YAML, JSON, or SQL.

| Property | Liquibase |
|----------|-----------|
| Migration format | XML / YAML / JSON / SQL |
| Rollback | Supported (explicit rollback blocks) |
| Mental model | Changeset DAG with author identity |
| Dependency footprint | Heavier (snakeyaml + transitive deps) |
| Spring Boot integration | First-class |
| Multi-database support | Yes (stronger abstraction) |
| Barrier to entry | Medium — changeset model requires learning |

### Why Flyway

1. **Additive-only evolution.** Schema changes add tables and columns; destructive DDL is
   avoided. Rollback support — Liquibase's main differentiator — is not a requirement: a
   deployment that needs to downgrade recreates the schema from scratch via a fresh Flyway run.

2. **SQL purity.** Plain SQL migrations are consistent with the no-JPA / no-DSL principle of
   this module. Any contributor who knows SQL can write a migration without learning a tool.

3. **Footprint.** `flyway-core` is a single self-contained JAR. Liquibase's transitive
   dependencies would widen the classpath of a module whose purpose is to stay lean.

4. **Contributor accessibility.** `V1__create_agent_directory.sql` is self-describing.
   Liquibase changesets with `id`, `author`, `preConditions`, and `context` blocks add
   cognitive overhead for occasional contributors.

5. **Spring Boot reuse.** The Spring Boot starter delegates to Spring Boot's native Flyway
   auto-configuration (`spring.flyway.*`) with zero additional wiring.

### Migration file placement

Each logical concern has its own Flyway configuration instance, with migrations under a
dedicated classpath location:

```
jentic-adapters-persistence/
└── src/main/resources/db/migration/
    ├── jentic-directory/         ← agent directory schema
    │   └── V1__create_agent_directory.sql
    └── jentic-hitl/              ← HITL schema (added with persistent HITL support)
        └── V1__create_hitl_requests.sql
```

Independent configuration instances allow directory and HITL migrations to be applied and
disabled selectively. `DirectorySchemaManager` runs only the `jentic-directory` location;
a future `HitlSchemaManager` runs only `jentic-hitl`.

---

## Schema Design

### Tables

```sql
-- V1__create_agent_directory.sql

CREATE TABLE jentic_agents (
    agent_id                VARCHAR(255)  PRIMARY KEY,
    agent_name              VARCHAR(255)  NOT NULL,
    agent_type              VARCHAR(255)  NOT NULL,
    status                  VARCHAR(50)   NOT NULL,
    node_id                 VARCHAR(255)  NOT NULL,
    endpoint_transport_type VARCHAR(100)  NOT NULL,
    endpoint_props          TEXT,                    -- JSON
    metadata                TEXT,                    -- JSON
    registered_at           TIMESTAMP     NOT NULL,
    last_seen               TIMESTAMP     NOT NULL
);

CREATE TABLE jentic_agent_capabilities (
    agent_id    VARCHAR(255)  NOT NULL REFERENCES jentic_agents(agent_id) ON DELETE CASCADE,
    capability  VARCHAR(255)  NOT NULL,
    PRIMARY KEY (agent_id, capability)
);

CREATE INDEX idx_jentic_agents_status ON jentic_agents(status);
CREATE INDEX idx_jentic_agents_type   ON jentic_agents(agent_type);
CREATE INDEX idx_jentic_agents_node   ON jentic_agents(node_id);
```

### Registration semantics: upsert

`agent_id` in the framework is a **stable logical identity**, not an ephemeral UUID. The
`Agent` interface Javadoc specifies: *"Recommended format: lowercase alphanumeric with hyphens
(e.g., `order-processor-1`)"*. The `BaseAgent(String agentId)` constructor and the
`@Agent` annotation value both reinforce this: agents are expected to carry the same
`agent_id` across restarts.

Consequence: `AgentRegistry.register()` must use **upsert semantics**, not a plain `INSERT`.
A plain `INSERT` would fail with a PK violation on any restart of an already-registered agent.

```sql
-- Postgres
INSERT INTO jentic_agents (agent_id, agent_name, agent_type, status, node_id,
                           endpoint_transport_type, endpoint_props, metadata,
                           registered_at, last_seen)
VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
ON CONFLICT (agent_id) DO UPDATE SET
    node_id                 = EXCLUDED.node_id,
    endpoint_transport_type = EXCLUDED.endpoint_transport_type,
    endpoint_props          = EXCLUDED.endpoint_props,
    status                  = EXCLUDED.status,
    last_seen               = EXCLUDED.last_seen;
    -- registered_at is intentionally NOT updated: it records first registration time
```

**SQL portability:**

| Database | Upsert syntax |
|----------|--------------|
| Postgres | `ON CONFLICT (agent_id) DO UPDATE SET ...` |
| MySQL / MariaDB | `ON DUPLICATE KEY UPDATE ...` |
| H2 | `MERGE INTO ... USING ... ON ...` or `ON CONFLICT` (H2 2.x supports both) |

`JdbcAgentRegistry` selects the upsert variant at construction time based on the JDBC URL
prefix (`jdbc:postgresql`, `jdbc:mysql`, `jdbc:h2`). The Flyway DDL itself is portable — only
the upsert DML differs per dialect.

**Singleton semantics:**

The PK constraint on `agent_id` encodes a deliberate invariant: **one active registration per
logical agent identity, across all nodes**. If node B registers `"payment-agent"` while node A
has an existing row for `"payment-agent"`, node B's upsert overwrites node A's `node_id` and
`endpoint`. From that moment, `AgentResolver.resolveEndpoint("payment-agent")` routes to node
B. Node A's instance of `payment-agent` becomes unreachable until it re-registers.

This is the correct behaviour for restart/migration scenarios. For horizontal scaling of the
same logical agent across multiple nodes simultaneously, callers must use distinct `agent_id`
values (e.g., `"payment-agent-1"`, `"payment-agent-2"`) and discover by type via
`AgentDiscovery.findByType`. A multi-endpoint resolver is deferred to the Enterprise tier.

### Design notes

- `agent_id` is a stable logical identity (see Registration semantics above) and the sole
  lookup key for `AgentResolver` — covered by the PK index, no additional index needed on the
  hot path.
- `AgentEndpoint` (from ADR-020) is stored as two columns: `endpoint_transport_type` (e.g.
  `"local"`, `"redis"`) and `endpoint_props` (JSON-serialised `Map<String,String>`).
  Reconstructed into an `AgentEndpoint` record on read. No JSONB-specific features are used
  so the schema works on Postgres, MySQL, and H2.
- `node_id` is a UUID generated once at `AgenorRuntime` startup. It identifies the owning JVM
  and is written into `AgentEndpoint.transportProps` for Redis-based point-to-point routing.
- `jentic_agent_capabilities` is normalised (not a JSON array) to allow
  `findByCapability(String)` to use an index scan rather than a JSON path expression.
- `endpoint_props` and `metadata` use `TEXT` (not `JSONB`) to preserve cross-database
  compatibility. Postgres users who need JSON indexing on these columns can apply a
  custom migration.
- `last_seen` is written on `register` and `updateStatus`. It is **not** updated by
  `AgentPresence.heartbeat` — the JDBC module does not implement heartbeat (see above).

---

## Implementation

### Classes

| Class | Interface(s) | Responsibility |
|-------|--------------|----------------|
| `JdbcAgentRegistry` | `AgentRegistry` | `register`, `unregister`, `updateStatus` |
| `JdbcAgentDiscovery` | `AgentDiscovery` | `findById`, `findByCapability`, `findByType`, `findAgents` |
| `JdbcAgentResolver` | `AgentResolver` | `resolveEndpoint` — single-row PK lookup |
| `JdbcDirectoryConfig` | — (record) | DataSource URL, pool size, schema location |
| `DirectorySchemaManager` | — | Runs Flyway on the `jentic-directory` location at startup |
| `JdbcHelper` | — | `PreparedStatement` execution, result mapping, shared by all three adapters |

Package root: `dev.agenor.adapters.persistence.directory`

### `JdbcHelper`

A thin internal utility — not public API — that wraps `DataSource.getConnection()`, handles
`SQLException → RuntimeException` wrapping, and provides typed result-set mappers for
`AgentDescriptor` and `AgentEndpoint`. Shared by all three adapter classes to avoid
duplicating connection-handling boilerplate.

### Multi-node behaviour

Two runtimes on the same database are fully independent writers. Each registers its own
agents with its own `node_id`. `AgentResolver.resolveEndpoint` returns the `AgentEndpoint`
stored at registration time — if an agent is local to node A, node B resolves it to node A's
transport endpoint and routes accordingly (via Redis transport or A2A, depending on what is
configured).

There is no distributed lock on `register` or `updateStatus`. Upsert is atomic at the
database level (row-level lock for the duration of the statement); no application-level
coordination is required. The singleton constraint (one row per `agent_id`) is enforced by
the PK and the upsert: whichever node registers last owns the routing entry for that identity.

### Observability

OTel spans emitted via `AgenorTelemetry` (ADR-019):

| Operation | Span name | Key attributes |
|-----------|-----------|----------------|
| `AgentRegistry.register` | `directory.register` | `agent.id`, `agent.type` |
| `AgentRegistry.updateStatus` | `directory.update_status` | `agent.id`, `agent.status` |
| `AgentResolver.resolveEndpoint` | `directory.resolve` | `agent.id`, `endpoint.type` |
| `AgentDiscovery.findByCapability` | `directory.find` | `query.capability` |
| `AgentDiscovery.findAgents` | `directory.find` | `query.type`, `page.size` |

### Testing

| Test type | Target | Tool |
|-----------|--------|------|
| Unit | `JdbcAgentRegistry`, `JdbcAgentDiscovery`, `JdbcAgentResolver` | H2 in-process |
| Integration | Full Postgres behaviour, multi-node scenario | Testcontainers Postgres |
| Integration | MySQL cross-DB compatibility | Testcontainers MySQL |
| Contract | Reuse `AgentRegistry`, `AgentDiscovery`, `AgentResolver` contract suites from ADR-020 | — |
| Classpath | Consumer without `jentic-adapters-persistence` builds and uses `InMemoryAgentDirectory` | Maven Invoker plugin |

Integration tests are gated by `-Dintegration.tests.enabled=true`, consistent with the
Redis adapter convention established in ADR-021.

---

## Consequences

**Positive:**

- Agent registrations survive JVM restarts; multiple nodes share a consistent directory view.
- `AgentResolver` latency is bounded by a single indexed PK lookup — predictable under load.
- Capability-based discovery (`findByCapability`) uses a normalised table with an implicit PK
  index; no full-table scan or JSON path evaluation.
- Pure JDBC keeps the module framework-agnostic and usable without Spring.
- The contract test suites from ADR-020 are reused unchanged, proving semantic equivalence
  with `InMemoryAgentDirectory` for the three implemented capabilities.

**Negative / trade-offs:**

- `AgentPresence` is not provided. Single-node deployments fall back to in-memory presence;
  multi-node liveness requires a dedicated presence backend (deferred to Enterprise).
- `endpoint_props` and `metadata` as `TEXT` columns lose Postgres JSONB indexing. Users who
  need it can apply a custom migration; the default schema favours cross-database portability.
- The singleton constraint (one registration per `agent_id`) means horizontal scaling of the
  same logical agent across multiple nodes simultaneously requires distinct `agent_id` values.
  This is a documented constraint, not a limitation to be worked around silently.

---

## Alternatives Considered

**Use Spring Data JDBC.**  
Rejected: introduces Spring Data Commons + Spring Context as hard compile dependencies,
coupling the module to the Spring ecosystem. The module must remain usable outside Spring.

**Use jOOQ for type-safe queries.**  
Rejected: requires a code-generation step and a significant compile-time dependency for a
small, stable query surface. `PreparedStatement` is sufficient and removes the code-gen step
from contributor setup.

**Use Liquibase instead of Flyway.**  
Rejected: Flyway's sequential plain-SQL model covers the additive-only schema evolution of
this module. Liquibase's rollback support and changeset DSL are not required, and its heavier
dependency footprint contradicts the lean-classpath goal of the module.

**Use composite PK `(agent_id, node_id)` to allow multiple nodes to register the same logical agent simultaneously.**  
Rejected for the OSS tier: `AgentResolver.resolveEndpoint(agentId)` would return multiple
endpoints and require tie-breaking logic (random selection, round-robin, liveness check).
This is load-balancing / multi-endpoint routing — a concern that belongs to the Enterprise
tier alongside clustering and leader election. The PK on `agent_id` alone keeps the resolver
trivial (single-row PK lookup) and makes routing behaviour predictable.

**Implement `AgentPresence` over JDBC with batched updates.**  
Rejected: batching reduces write frequency but does not eliminate WAL pressure, autovacuum
churn, or the lock contention that makes relational stores a poor fit for high-frequency
liveness signals. Presence semantics are also fundamentally different from registry
semantics (TTL-based vs explicit write), so conflating them in one backend leads to
accidental coupling.

---

## Related ADRs

- ADR-001: Virtual threads — all JDBC operations run on virtual threads; blocking calls are safe
- ADR-002: Interface-first architecture — module implements only the capability subset it supports
- ADR-004: Progressive complexity — in-memory directory remains the default; JDBC is opt-in
- ADR-019: OpenTelemetry instrumentation — span taxonomy for directory operations
- ADR-020: Core API refactor — `AgentRegistry`, `AgentDiscovery`, `AgentResolver`, `AgentPresence`
  are the interfaces this ADR reasons about; contract test suites are reused from ADR-020
- ADR-022: Module split — placement rationale for `jentic-adapters-persistence`
