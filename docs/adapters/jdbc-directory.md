# JDBC Agent Directory

Implements `AgentRegistry`, `AgentDiscovery`, and `AgentResolver` on top of a relational
database (PostgreSQL, MySQL, H2) via plain JDBC. Provides durable agent registration,
cross-node capability discovery, and endpoint resolution across JVM restarts — without
requiring additional infrastructure beyond a JDBC-compatible database.

`JdbcAgentDirectory` is the primary entry point. It owns the HikariCP connection pool,
runs Flyway schema migrations on startup, and exposes the three JDBC capability
implementations. `AgentPresence` (heartbeat/liveness) is intentionally **not** backed by
JDBC — see [Why presence is not JDBC-backed](#why-presence-is-not-jdbc-backed).

Architectural rationale: ADR-022 (module split), ADR-023 (persistent agent directory)

---

## Prerequisites

Any JDBC-compatible database. Recommended:

```bash
# PostgreSQL via Docker — recommended for production
docker run -d -p 5432:5432 \
  -e POSTGRES_DB=jentic \
  -e POSTGRES_USER=jentic \
  -e POSTGRES_PASSWORD=jentic_pass \
  postgres:16-alpine

# or with docker compose (if compose.yml is present):
docker compose up postgres
```

For development/testing without external infrastructure, H2 in-process mode is fully
supported (see the example below and `JdbcDirectoryExample` in `jentic-examples`).

---

## Maven dependency (opt-in)

`jentic-adapters-persistence` is a dedicated Maven module per ADR-022 (Optional Adapter
Dependencies Pattern). Consumers that want a JDBC-backed directory must declare it
explicitly:

```xml
<dependency>
    <groupId>dev.agenor</groupId>
    <artifactId>jentic-adapters-persistence</artifactId>
    <version>${jentic.version}</version>
</dependency>
<!-- Runtime JDBC driver — choose one -->
<dependency>
    <groupId>org.postgresql</groupId>
    <artifactId>postgresql</artifactId>
    <version>42.7.5</version>
    <scope>runtime</scope>
</dependency>
```

Consumers that declare only `jentic-runtime` continue to use `InMemoryAgentDirectory` —
no configuration required, no classpath impact.

---

## Schema

Flyway manages the schema automatically. The first migration
(`V1__create_agent_directory.sql`) creates:

| Table | Primary key | Purpose |
|---|---|---|
| `jentic_agents` | `agent_id` | Stores agent registration, status, endpoint, and metadata |
| `jentic_agent_capabilities` | `(agent_id, capability)` | Normalised capability set; FK cascade-deletes on agent removal |

The migration location defaults to `classpath:db/migration/jentic-directory` and runs
on every `JdbcAgentDirectory.create()` call. Flyway's `baselineOnMigrate=true` makes
the migration idempotent — safe to call repeatedly.

---

## Quick start

### Programmatic (any application)

```java
import dev.agenor.adapters.persistence.directory.JdbcAgentDirectory;
import dev.agenor.adapters.persistence.directory.JdbcDirectoryConfig;
import dev.agenor.runtime.JenticRuntime;

var config = JdbcDirectoryConfig.of(
        "jdbc:postgresql://localhost:5432/jentic",
        "jentic", System.getenv("DB_PASSWORD"));

try (var dir = JdbcAgentDirectory.create(config)) {
    var runtime = JenticRuntime.builder()
            .agentRegistry(dir.registry())
            .agentDiscovery(dir.discovery())
            .agentResolver(dir.resolver())
            // AgentPresence falls back to in-memory (see below)
            .build();
    runtime.start().join();
    // ...
    runtime.stop().join();
}
```

### Spring Boot auto-configuration

Add the dependency and configure `jentic.directory.provider=jdbc`. Provider-specific
properties go in the `properties` map:

```yaml
jentic:
  directory:
    provider: jdbc
    jdbc:
      url: jdbc:postgresql://localhost:5432/jentic
      username: jentic
      password: ${DB_PASSWORD}
      pool-size: 10
```

The auto-configuration activates when `JdbcAgentDirectory` is on the classpath **and**
`provider=jdbc` is set. See `JenticAutoConfiguration.JdbcDirectoryConfiguration` for
the exact conditions.

---

## Configuration reference

`JdbcDirectoryConfig` accepts four parameters (all accessible via `JdbcDirectoryConfig.of(url, user, pass)`):

| Property | Default | Description |
|---|---|---|
| `jdbcUrl` | — (required) | JDBC connection URL |
| `username` | — | Database username |
| `password` | — | Database password |
| `maximumPoolSize` | `10` | HikariCP max pool size |
| `migrationLocation` | `classpath:db/migration/jentic-directory` | Flyway migration classpath location |

Spring Boot YAML keys (`jentic.directory.jdbc.*`):

| Key | Default | Description |
|---|---|---|
| `jentic.directory.jdbc.url` | — (required when `provider=jdbc`) | JDBC URL |
| `jentic.directory.jdbc.username` | — | Database username |
| `jentic.directory.jdbc.password` | — | Database password |
| `jentic.directory.jdbc.pool-size` | `10` | HikariCP max pool size |

---

## Mixed backend: JDBC directory + in-memory presence

The three JDBC capabilities (registry, discovery, resolver) pair with the default
in-memory presence. This is the recommended setup: JDBC for durability, in-memory for
low-latency heartbeats.

```java
var runtime = JenticRuntime.builder()
        .agentRegistry(dir.registry())
        .agentDiscovery(dir.discovery())
        .agentResolver(dir.resolver())
        // No .agentPresence() call → runtime fills in InMemoryAgentDirectory
        .build();
```

The `JenticRuntime.Builder` assembles a `CompositeAgentDirectory` from the three JDBC
capabilities plus the default in-memory `AgentPresence`. The result satisfies the full
`AgentDirectory` interface without any changes to agent code.

---

## Why presence is not JDBC-backed

`AgentPresence` (`heartbeat` + `getStatus`) has fundamentally different access patterns
from registration and discovery:

- **Write frequency**: a heartbeat `UPDATE` fires every few seconds per agent. At 100
  agents, that is hundreds of writes per minute to a table that is also read on every
  `sendTo` call. PostgreSQL is not the right tool for sub-second liveness signalling.
- **Fitness**: liveness is better served by Redis TTL keys, Consul session leases, or
  ZooKeeper ephemeral nodes — backends designed for heartbeat workloads.
- **Operational independence**: registry/discovery data (capabilities, metadata, endpoints)
  changes slowly and survives restarts; presence data is transient by design.

The capability split from ADR-020 makes this separation first-class. A future
`RedisAgentPresence` (not yet implemented) would plug in via `.agentPresence(redisPresence)`
with no changes to the JDBC or runtime code.

---

## Multi-node scenario

Two JVMs can share the same database to form a logical cluster:

```
Node A                          Node B
  │                               │
  ├── registers "agent-a"         ├── registers "agent-b"
  │   (endpoint: local/node-a)    │   (endpoint: local/node-b)
  │                               │
  └── discovers "agent-b"         └── discovers "agent-a"
      via JdbcAgentDiscovery           via JdbcAgentDiscovery
```

`JdbcAgentResolver.resolveEndpoint("agent-b")` on Node A returns the endpoint stored
by Node B. Cross-JVM point-to-point delivery requires a matching transport adapter
(e.g. the Redis `MessageTransport`) that can route to remote endpoints.
Within a single JVM the in-memory fast-path applies regardless of which directory
backend is used.

---

## Integration tests

Testcontainers-based PostgreSQL integration tests are in `JdbcAgentDirectoryIT`:

```bash
mvn verify -pl jentic-adapters-persistence -Dintegration.tests.enabled=true
```

Unit tests against an embedded H2 database run as part of `mvn test` (no flag required):

```bash
mvn test -pl jentic-adapters-persistence
```
