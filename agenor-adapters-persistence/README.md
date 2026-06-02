# agenor-adapters-persistence

JDBC-backed persistence adapters for the Jentic multi-agent framework.

This module provides durable storage for agent directory data (registry, discovery,
endpoint resolution) and HITL approval queues using a relational database
(PostgreSQL, MySQL, H2) via plain JDBC.
It is a dedicated Maven module rather than an `optional=true` dependency in
`agenor-adapters` because the persistence stack (HikariCP, Flyway, JDBC drivers) is
heavyweight infrastructure that should not reach the default classpath — see ADR-022.

---

## Capabilities

### Agent directory (since 0.22.0)

| Capability | Interface | Implementation |
|---|---|---|
| Agent registration | `AgentRegistry` | `JdbcAgentRegistry` |
| Agent discovery | `AgentDiscovery` | `JdbcAgentDiscovery` |
| Endpoint resolution | `AgentResolver` | `JdbcAgentResolver` |
| Presence / liveness | `AgentPresence` | **Not implemented** — use in-memory (see ADR-023) |

### HITL approval queue (since 0.23.0)

| Capability | Interface | Implementation |
|---|---|---|
| Approval gate | `ApprovalGate` | `JdbcApprovalGate` |

---

## Quick start — Agent directory

```java
import dev.agenor.adapters.persistence.directory.JdbcAgentDirectory;
import dev.agenor.adapters.persistence.directory.JdbcDirectoryConfig;
import dev.agenor.runtime.AgenorRuntime;

var dir = JdbcAgentDirectory.create(
        JdbcDirectoryConfig.of("jdbc:postgresql://localhost:5432/mydb", user, pass));

var runtime = AgenorRuntime.builder()
        .agentRegistry(dir.registry())
        .agentDiscovery(dir.discovery())
        .agentResolver(dir.resolver())
        .build();
```

See `agenor-examples/.../jdbc/JdbcDirectoryExample.java` for a runnable example.

## Quick start — Persistent HITL

```java
import dev.agenor.adapters.persistence.hitl.HitlSchemaManager;
import dev.agenor.adapters.persistence.hitl.JdbcApprovalGate;

new HitlSchemaManager(dataSource, "classpath:db/migration/agenor-hitl").migrate();

var gate = new JdbcApprovalGate(dataSource, jdbcUrl);
gate.recoverExpired();   // mark stale rows EXPIRED on startup

var runtime = AgenorRuntime.builder()
        .withDefaultConfig()
        .approvalGate(gate)
        .build();
```

See `agenor-examples/.../hitl/PersistentHitlExample.java` for a runnable example.

---

## Maven dependency

```xml
<dependency>
    <groupId>dev.agenor</groupId>
    <artifactId>agenor-adapters-persistence</artifactId>
    <version>${jentic.version}</version>
</dependency>
<!-- Choose a JDBC driver -->
<dependency>
    <groupId>org.postgresql</groupId>
    <artifactId>postgresql</artifactId>
    <version>42.7.5</version>
    <scope>runtime</scope>
</dependency>
```

---

## Spring Boot auto-configuration

### JDBC agent directory

```yaml
jentic:
  directory:
    provider: jdbc
    jdbc:
      url: jdbc:postgresql://localhost:5432/mydb
      username: jentic
      password: ${DB_PASSWORD}
```

### Persistent HITL approval queue

```yaml
jentic:
  hitl:
    provider: jdbc
    jdbc:
      url: jdbc:postgresql://localhost:5432/mydb   # falls back to directory.jdbc.url
      username: jentic
      password: ${DB_PASSWORD}
      pool-size: 5
```

Both features can share a single `url` via `jentic.directory.jdbc.url` — the HITL
configuration reads it as a fallback when `jentic.hitl.jdbc.url` is not set.

---

## Schema

Flyway manages all DDL via two migration locations:

| Location | Tables | Feature |
|---|---|---|
| `classpath:db/migration/agenor-directory` | `jentic_agents`, `jentic_agent_capabilities` | Agent directory |
| `classpath:db/migration/agenor-hitl`      | `jentic_hitl_requests` | HITL approval queue |

Migrations run automatically on factory method / constructor invocation. They are idempotent.

Supported databases: **PostgreSQL** (primary), **MySQL / MariaDB** (optional driver),
**H2** (tests and development).

---

## Tests

```bash
# Unit tests (H2 in-process — no external infrastructure required)
mvn test -pl agenor-adapters-persistence

# Integration tests (Testcontainers PostgreSQL)
mvn verify -pl agenor-adapters-persistence -Dintegration.tests.enabled=true
```

---

## ADRs

- **ADR-022** — `agenor-adapters-persistence` module split (rationale for dedicated module vs `optional=true`)
- **ADR-023** — Persistent agent directory with JDBC (schema design, upsert semantics, presence trade-off)
- **ADR-024** — Persistent HITL approval queue (JDBC) (recovery semantics, cross-node LISTEN/NOTIFY)
