# ADR-024: Persistent HITL Approval Queue (JDBC)

**Status**: Accepted  
**Date**: 2026-05-22  
**Authors**: Project Team  
**References**: ADR-001 (Virtual Threads), ADR-004 (Progressive Complexity),
ADR-015 (Human-In-The-Loop Checkpoint), ADR-022 (`jentic-adapters-persistence` Module Split),
ADR-023 (Persistent Agent Directory with JDBC)

---

## Context

`InMemoryApprovalGate` loses all pending approval requests on JVM restart. For production HITL
workflows with human-scale timeouts (minutes, hours, days), this is unacceptable: a restart
mid-approval silently discards the pending request and the submitting agent either blocks
forever or times out without a trace.

The `jentic-adapters-persistence` module introduced for ADR-023 already provides Hikari,
Flyway, and a Postgres/H2-compatible JDBC stack. Extending this module with a persistent
`ApprovalGate` implementation requires no new Maven artifact and no new infrastructure
dependency.

---

## Decision

### `JdbcApprovalGate`

A new class `dev.agenor.adapters.persistence.hitl.JdbcApprovalGate` implements `ApprovalGate`.

**`requestApproval(ApprovalRequest request)`**:
1. INSERT a row into `jentic_hitl_requests` with `status = PENDING`.
2. Register a `CompletableFuture<ApprovalDecision>` in an in-memory `ConcurrentHashMap` keyed
   by `requestId` — this future lives only in the JVM that submitted the request.
3. Schedule a timeout callback (virtual thread, ADR-001) that marks the row `EXPIRED` and
   completes the future exceptionally with `ApprovalTimeoutException` if no decision arrives.
4. Return the future.

**`submit(String requestId, ApprovalDecision decision)`**:
1. UPDATE the row: set `status`, `decision_type`, `decision_data`, `decided_at`.
2. If a local future is registered: complete it with the decision.
3. If Postgres is detected (JDBC URL contains `postgresql`): emit
   `NOTIFY jentic_hitl, '<requestId>:<decisionJson>'` so other JVMs listening on this
   channel can complete their local futures.

**`getPendingRequests()`**:
Returns a list of `ApprovalRequest` rows with `status = PENDING` and `expires_at > NOW()`.
This query spans all JVMs — a dashboard on any node sees every pending request.

**Startup recovery** (`recoverExpired()`):
On gate construction, scan `PENDING` rows with `expires_at <= NOW()` and mark them `EXPIRED`.
Rows from dead nodes that have not yet expired are left untouched until their natural expiry —
their `CompletableFuture` is gone, but the audit record is preserved.

### Schema

```sql
CREATE TABLE jentic_hitl_requests (
    request_id      VARCHAR(255) NOT NULL,
    agent_id        VARCHAR(255) NOT NULL,
    action          VARCHAR(255) NOT NULL,
    payload         TEXT,
    metadata        TEXT,
    created_at      TIMESTAMP    NOT NULL,
    expires_at      TIMESTAMP    NOT NULL,
    status          VARCHAR(50)  NOT NULL,  -- PENDING | APPROVED | REJECTED | MODIFIED | EXPIRED
    decision_type   VARCHAR(50),
    decision_data   TEXT,
    decided_at      TIMESTAMP,
    decided_by      VARCHAR(255),
    CONSTRAINT pk_jentic_hitl_requests PRIMARY KEY (request_id)
);
```

Managed by a new Flyway migration location: `classpath:db/migration/jentic-hitl`.

### Cross-node propagation (Postgres-only, optional)

`PostgresNotificationListener` opens a dedicated non-pooled `Connection`, executes
`LISTEN jentic_hitl`, and runs a poll loop on a virtual thread. When a `NOTIFY` arrives,
it parses the payload as `requestId:decisionJson`, looks up the local future map, and
completes the future if present. This is a best-effort delivery: if the originating JVM
restarts before the decision is submitted by another node, the new JVM finds the row via
`getPendingRequests()` but has no local future to complete.

For non-Postgres databases the listener is not started; cross-node propagation requires
explicit polling via `getPendingRequests()` + re-submission.

### Module placement

`JdbcApprovalGate` lives in `jentic-adapters-persistence` (package
`dev.agenor.adapters.persistence.hitl`), reusing the Hikari/Flyway/JDBC infrastructure
already present. No pom.xml change is needed.

### `AgenorRuntime` builder

A new `approvalGate(ApprovalGate gate)` setter is added to `AgenorRuntime.Builder`. The
default (`InMemoryApprovalGate`) is used when no gate is provided.

### Spring Boot starter

A new `JdbcHitlConfiguration` inner class in `AgenorAutoConfiguration` activates when:
- `dev.agenor.adapters.persistence.hitl.JdbcApprovalGate` is on the classpath
- `jentic.hitl.provider=jdbc` is set

YAML:
```yaml
jentic:
  hitl:
    provider: jdbc        # default: inmemory
    jdbc:
      url: jdbc:postgresql://localhost:5432/mydb  # reuses directory.jdbc.url if set
```

The `JdbcApprovalGate` bean is exposed as `ApprovalGate` and wired into `AgenorRuntime` via
the new builder setter.

---

## Consequences

### Positive

- Pending approvals survive JVM restarts and are visible from any node via `getPendingRequests()`.
- Expired-on-restart rows are reconciled automatically, producing clean audit entries.
- The implementation reuses the existing Hikari/Flyway stack — zero new Maven dependencies.
- `InMemoryApprovalGate` remains the default; existing users see no change.
- Cross-node decision propagation is available for Postgres without external coordination.

### Negative / trade-offs

- The `CompletableFuture` lives only in the submitting JVM. A node crash before a decision
  arrives means the future is lost — the decision can still be submitted from any node and
  recorded in the DB, but the original agent will not receive it after restart. This is
  documented as a known constraint; full event-sourced replay is deferred to the Enterprise tier.
- Polling `getPendingRequests()` on non-Postgres databases is the only cross-node synchronisation
  mechanism; pull-based, not push-based.

### Out of scope (deferred to Enterprise)

- Full event-sourced HITL replay (agent re-registers future after restart via persistent request ID)
- Consul / NATS / Kafka-based HITL propagation
- Distributed timeout coordination (currently each JVM sets its own local timeout)
