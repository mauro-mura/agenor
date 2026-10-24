# Persistent HITL Approval Queue

> **Reference ADR**: ADR-024 — Persistent HITL Approval Queue (JDBC) (`docs/adr/`)  
> **Since**: 0.23.0  
> **Module**: `agenor-adapters-persistence`

---

## Overview

The default `InMemoryApprovalGate` loses all pending approval requests when the JVM restarts.
For production HITL workflows with human-scale timeouts (minutes, hours, days), this is
unacceptable.

`JdbcApprovalGate` persists every approval request in the `agenor_hitl_requests` table.
It reuses the same `agenor-adapters-persistence` module used by the JDBC agent directory
(ADR-023), so no new Maven dependency is required if you already have the module on your
classpath.

---

## Quick start

### 1. Add the dependency

```xml
<dependency>
    <groupId>dev.agenor</groupId>
    <artifactId>agenor-adapters-persistence</artifactId>
    <version>0.38.0</version>
</dependency>
```

PostgreSQL is the primary database: its JDBC driver and Flyway support come with the module, so
there is nothing else to declare. On MySQL, add the two dependencies in
[JDBC Agent Directory § On MySQL](adapters/jdbc-directory.md#on-mysql). For H2, declare
`com.h2database:h2` yourself, usually in `test` scope.

### 2. Wire the gate manually (without Spring Boot)

```java
var cfg = new HikariConfig();
cfg.setJdbcUrl("jdbc:postgresql://localhost:5432/mydb");
cfg.setUsername("agenor");
cfg.setPassword("secret");
var dataSource = new HikariDataSource(cfg);

// Run the Flyway migration (idempotent)
new HitlSchemaManager(dataSource, "classpath:db/agenor/hitl").migrate();

// Create and initialise the gate
var gate = new JdbcApprovalGate(dataSource, cfg.getJdbcUrl());
gate.recoverExpired();  // mark stale rows EXPIRED on startup

// Wire into the runtime
var runtime = AgenorRuntime.builder()
        .withDefaultConfig()
        .approvalGate(gate)
        .build();
```

### 3. Wire via Spring Boot

```yaml
agenor:
  hitl:
    provider: jdbc
    jdbc:
      url: jdbc:postgresql://localhost:5432/mydb
      username: agenor
      password: ${DB_PASSWORD}
      pool-size: 5   # default: 5
```

When `agenor.hitl.provider=jdbc` and `agenor-adapters-persistence` is on the classpath,
the auto-configuration creates the `JdbcApprovalGate` bean and wires it into `AgenorRuntime`
automatically.

If `agenor.hitl.jdbc.url` is not set, the auto-configuration falls back to
`agenor.directory.jdbc.url` (so both features can share a single connection pool config).

---

## Schema

The schema is managed by Flyway from location `classpath:db/agenor/hitl`, with
its own history table, `agenor_hitl_schema_history`. It can therefore share a database with the
JDBC directory, which is what the URL fallback above does. Up to 0.37.0 that combination failed at
startup with a Flyway checksum mismatch for version 1. A database migrated by 0.37.0 or earlier is
picked up without any action. The location was `classpath:db/migration/agenor-hitl` up to
0.37.0, and the old one still works with a warning until 1.0.0. See
[JDBC Agent Directory § Schema](adapters/jdbc-directory.md#schema).

```sql
CREATE TABLE agenor_hitl_requests (
    request_id      VARCHAR(255) NOT NULL,
    agent_id        VARCHAR(255) NOT NULL,
    action          VARCHAR(255) NOT NULL,
    payload         TEXT,           -- serialised action payload
    metadata        TEXT,           -- JSON key-value pairs
    created_at      TIMESTAMP    NOT NULL,
    expires_at      TIMESTAMP    NOT NULL,
    status          VARCHAR(50)  NOT NULL,  -- see below
    decision_type   VARCHAR(50),
    decision_data   TEXT,           -- see below
    decided_at      TIMESTAMP,
    decided_by      VARCHAR(255),
    CONSTRAINT pk_agenor_hitl_requests PRIMARY KEY (request_id)
);
```

`payload` and `decision_data` hold text as it was given, or JSON otherwise. A `String` payload is
stored unchanged and any other object as Jackson JSON. `decision_data` depends on
`decision_type`: empty for `APPROVED`, the reason for `REJECTED`, and the new payload for
`MODIFIED`, stored the same way as `payload`. Through the gate, `ApprovalRequest.payloadAs(Class)`
and `ApprovalDecision.Modified.newPayloadAs(Class)` read either form back into your type.

The `TIMESTAMP` columns hold UTC wall-clock times, here and in `agenor_agents`, whatever the
time zone of the node that wrote them. Read them as UTC if you query the table yourself; in
JDBC, `rs.getTimestamp(column, Calendar.getInstance(TimeZone.getTimeZone("UTC")))`.

### Status values

| Status     | Meaning                                            |
|------------|----------------------------------------------------|
| `PENDING`  | Awaiting a human decision                          |
| `APPROVED` | Approved, with the original payload                |
| `REJECTED` | Rejected                                           |
| `MODIFIED` | Approved with modified payload                     |
| `EXPIRED`  | Timed out before a decision was submitted          |

A status records the decision, not what the agent did with it: after a restart the decision can
be stored and never reach the agent (see [Local-future constraint](#local-future-constraint)).

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

A request read back from the database carries its payload as the JSON that was stored, not as
the object passed to `requestApproval`; a `Modified` decision submitted on another node reaches
the waiting future the same way. Read both with `payloadAs(Class)` and `newPayloadAs(Class)`,
which give the same result on the in-memory gate. A cast works in memory and throws here:

```java
for (ApprovalRequest pending : gate.getPendingRequests()) {
    Draft draft = pending.payloadAs(Draft.class);   // not (Draft) pending.payload()
}
```

### Postgres LISTEN/NOTIFY (automatic)

When the JDBC URL contains `postgresql`, `JdbcApprovalGate` activates
`PostgresNotificationListener`. It opens a dedicated connection and listens on the
`agenor_hitl` channel. When another JVM submits a decision, it emits
`NOTIFY agenor_hitl, '<requestId>:<decisionJson>'`. The listener picks this up and completes
the local future on the receiving JVM.

This provides **push-based cross-node propagation** without polling.

### Non-Postgres databases

For H2, MySQL, and other databases there is no cross-node propagation: a decision reaches the
waiting agent only when it is submitted on the JVM that requested it. Submitted anywhere else,
it is stored and goes no further, and nothing polls for it — once decided, the request no longer
appears in `getPendingRequests()`. Run the operator UI on the node whose agents ask, or use
Postgres.

---

## Known constraints

### Local-future constraint

The `CompletableFuture` returned by `requestApproval()` lives only in the JVM that called
it. If that JVM crashes before the decision arrives:

- The agent's future is lost — the agent will not receive the decision.
- The DB row remains with `PENDING` status until it expires.
- Any operator can still submit a decision from another node (the row is visible via
  `getPendingRequests()`), but the originating agent will not see it after restart.

What an operator meets is the decision side of this. A request made before the restart is still
`PENDING`, still listed by `getPendingRequests()`, and can still be decided; the row then says
`APPROVED`, `REJECTED` or `MODIFIED`, with `decision_type`, `decision_data` and `decided_at`
filled in as described under [Schema](#schema), and nothing else happens. The action the agent
was waiting to take is not taken.

Without Postgres NOTIFY, `submit` knows this is the case and says so at WARN:

```
Decision on requestId=… (action=send-reply) is stored but will not be delivered: no caller in
this JVM is waiting for it, typically because the node that requested it restarted
```

With NOTIFY it cannot tell: the future may live on another node, which the notification
reaches. It logs at DEBUG, and a decision whose requester restarted goes unreported.

To find such decisions, query `agenor_hitl_requests` for `APPROVED`, `REJECTED` and `MODIFIED`
rows. The table does not record whether a decision was delivered, so telling the ones the agent
acted on from the ones it never saw takes a mark your application keeps itself, and acting on
them is up to the application too.

This is a documented trade-off. Full event-sourced replay (agent re-registers a future for
an existing `requestId` after restart) is out of scope.

---

## Configuration reference

| Property | Default | Description |
|----------|---------|-------------|
| `agenor.hitl.provider` | `inmemory` | HITL backend: `inmemory` or `jdbc` |
| `agenor.hitl.jdbc.url` | — | JDBC URL (falls back to `agenor.directory.jdbc.url`) |
| `agenor.hitl.jdbc.username` | `""` | Database username |
| `agenor.hitl.jdbc.password` | `""` | Database password |
| `agenor.hitl.jdbc.pool-size` | `5` | HikariCP pool size |

---

## Running the example

```bash
mvn exec:java -pl agenor-examples \
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
