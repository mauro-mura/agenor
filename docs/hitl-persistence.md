# Persistent HITL Approval Queue

> **Reference ADR**: ADR-024 — Persistent HITL Approval Queue (JDBC) (`docs/adr/`)  
> **Since**: 0.23.0  
> **Module**: `jentic-adapters-persistence`

---

## Overview

The default `InMemoryApprovalGate` loses all pending approval requests when the JVM restarts.
For production HITL workflows with human-scale timeouts (minutes, hours, days), this is
unacceptable.

`JdbcApprovalGate` persists every approval request in the `jentic_hitl_requests` table.
It reuses the same `jentic-adapters-persistence` module used by the JDBC agent directory
(ADR-023), so no new Maven dependency is required if you already have the module on your
classpath.

---

## Quick start

### 1. Add the dependency

```xml
<dependency>
    <groupId>dev.agenor</groupId>
    <artifactId>jentic-adapters-persistence</artifactId>
    <version>0.23.0</version>
</dependency>
<!-- PostgreSQL driver (or H2 for testing) -->
<dependency>
    <groupId>org.postgresql</groupId>
    <artifactId>postgresql</artifactId>
    <version>42.7.5</version>
    <scope>runtime</scope>
</dependency>
```

### 2. Wire the gate manually (without Spring Boot)

```java
var cfg = new HikariConfig();
cfg.setJdbcUrl("jdbc:postgresql://localhost:5432/mydb");
cfg.setUsername("jentic");
cfg.setPassword("secret");
var dataSource = new HikariDataSource(cfg);

// Run the Flyway migration (idempotent)
new HitlSchemaManager(dataSource, "classpath:db/migration/jentic-hitl").migrate();

// Create and initialise the gate
var gate = new JdbcApprovalGate(dataSource, cfg.getJdbcUrl());
gate.recoverExpired();  // mark stale rows EXPIRED on startup

// Wire into the runtime
var runtime = JenticRuntime.builder()
        .withDefaultConfig()
        .approvalGate(gate)
        .build();
```

### 3. Wire via Spring Boot

```yaml
jentic:
  hitl:
    provider: jdbc
    jdbc:
      url: jdbc:postgresql://localhost:5432/mydb
      username: jentic
      password: ${DB_PASSWORD}
      pool-size: 5   # default: 5
```

When `jentic.hitl.provider=jdbc` and `jentic-adapters-persistence` is on the classpath,
the auto-configuration creates the `JdbcApprovalGate` bean and wires it into `JenticRuntime`
automatically.

If `jentic.hitl.jdbc.url` is not set, the auto-configuration falls back to
`jentic.directory.jdbc.url` (so both features can share a single connection pool config).

---

## Schema

The schema is managed by Flyway from location `classpath:db/migration/jentic-hitl`.

```sql
CREATE TABLE jentic_hitl_requests (
    request_id      VARCHAR(255) NOT NULL,
    agent_id        VARCHAR(255) NOT NULL,
    action          VARCHAR(255) NOT NULL,
    payload         TEXT,           -- serialised action payload
    metadata        TEXT,           -- JSON key-value pairs
    created_at      TIMESTAMP    NOT NULL,
    expires_at      TIMESTAMP    NOT NULL,
    status          VARCHAR(50)  NOT NULL,  -- see below
    decision_type   VARCHAR(50),
    decision_data   TEXT,           -- rejection reason or modified payload
    decided_at      TIMESTAMP,
    decided_by      VARCHAR(255),
    CONSTRAINT pk_jentic_hitl_requests PRIMARY KEY (request_id)
);
```

### Status values

| Status     | Meaning                                            |
|------------|----------------------------------------------------|
| `PENDING`  | Awaiting a human decision                          |
| `APPROVED` | Approved; agent proceeded with original payload    |
| `REJECTED` | Rejected; agent did not execute the action         |
| `MODIFIED` | Approved with modified payload                     |
| `EXPIRED`  | Timed out before a decision was submitted          |

---

## Recovery on restart

Call `gate.recoverExpired()` once after construction. It scans `PENDING` rows whose
`expires_at` has passed and marks them `EXPIRED`. This is safe to call on every startup
(idempotent).

```java
var gate = new JdbcApprovalGate(dataSource, jdbcUrl);
gate.recoverExpired();  // must be called before handing the gate to agents
```

The Spring Boot auto-configuration calls this automatically.

---

## Cross-node behaviour

`getPendingRequests()` queries the database — it returns requests from **all** JVMs, not just
the current node. This means a dashboard or operator UI on any node can see every pending
request and submit decisions.

### Postgres LISTEN/NOTIFY (automatic)

When the JDBC URL contains `postgresql`, `JdbcApprovalGate` activates
`PostgresNotificationListener`. It opens a dedicated connection and listens on the
`jentic_hitl` channel. When another JVM submits a decision, it emits
`NOTIFY jentic_hitl, '<requestId>:<decisionJson>'`. The listener picks this up and completes
the local future on the receiving JVM.

This provides **push-based cross-node propagation** without polling.

### Non-Postgres databases

For H2, MySQL, and other databases, cross-node propagation is polling-based. A node that
does not hold the local future will not receive push notifications, but the decision is
always stored in the DB and is visible via `getPendingRequests()`.

---

## Known constraints

### Local-future constraint

The `CompletableFuture` returned by `requestApproval()` lives only in the JVM that called
it. If that JVM crashes before the decision arrives:

- The agent's future is lost — the agent will not receive the decision.
- The DB row remains with `PENDING` status until it expires.
- Any operator can still submit a decision from another node (the row is visible via
  `getPendingRequests()`), but the originating agent will not see it after restart.

This is a documented trade-off. Full event-sourced replay (agent re-registers a future for
an existing `requestId` after restart) is deferred to the Enterprise tier.

---

## Configuration reference

| Property | Default | Description |
|----------|---------|-------------|
| `jentic.hitl.provider` | `inmemory` | HITL backend: `inmemory` or `jdbc` |
| `jentic.hitl.jdbc.url` | — | JDBC URL (falls back to `jentic.directory.jdbc.url`) |
| `jentic.hitl.jdbc.username` | `""` | Database username |
| `jentic.hitl.jdbc.password` | `""` | Database password |
| `jentic.hitl.jdbc.pool-size` | `5` | HikariCP pool size |

---

## Running the example

```bash
mvn exec:java -pl jentic-examples \
  -Dexec.mainClass="dev.agenor.examples.hitl.PersistentHitlExample"
```

The example uses an in-process H2 database — no Docker or external database required.
For a production scenario, replace the H2 URL with a real Postgres connection string.

---

## See also

- [HITL Checkpoint guide](behaviors/hitl.md) — core HITL concepts and `InMemoryApprovalGate`
- ADR-015 — Human-in-the-Loop Checkpoint design (`docs/adr/`)
- ADR-024 — Persistent HITL design decisions (`docs/adr/`)
- [JDBC Directory](adapters/jdbc-directory.md) — the sibling persistence feature
