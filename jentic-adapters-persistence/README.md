# jentic-adapters-persistence

JDBC-backed persistence adapters for the Jentic multi-agent framework.

This module provides durable storage for agent directory data (registry, discovery,
endpoint resolution) using a relational database (PostgreSQL, MySQL, H2) via plain JDBC.
It is a dedicated Maven module rather than an `optional=true` dependency in
`jentic-adapters` because the persistence stack (HikariCP, Flyway, JDBC drivers) is
heavyweight infrastructure that should not reach the default classpath — see ADR-022.

---

## Capabilities

| Capability | Interface | Implementation |
|---|---|---|
| Agent registration | `AgentRegistry` | `JdbcAgentRegistry` |
| Agent discovery | `AgentDiscovery` | `JdbcAgentDiscovery` |
| Endpoint resolution | `AgentResolver` | `JdbcAgentResolver` |
| Presence / liveness | `AgentPresence` | **Not implemented** — use in-memory (see ADR-023) |

---

## Quick start

```java
import dev.jentic.adapters.persistence.directory.JdbcAgentDirectory;
import dev.jentic.adapters.persistence.directory.JdbcDirectoryConfig;
import dev.jentic.runtime.JenticRuntime;

var dir = JdbcAgentDirectory.create(
        JdbcDirectoryConfig.of("jdbc:postgresql://localhost:5432/mydb", user, pass));

var runtime = JenticRuntime.builder()
        .agentRegistry(dir.registry())
        .agentDiscovery(dir.discovery())
        .agentResolver(dir.resolver())
        .build();
```

See `jentic-examples/src/main/java/.../jdbc/JdbcDirectoryExample.java` for a runnable
example using H2 in-process (no Docker required).

---

## Maven dependency

```xml
<dependency>
    <groupId>dev.jentic</groupId>
    <artifactId>jentic-adapters-persistence</artifactId>
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

Add the dependency and set `jentic.directory.provider=jdbc` in `application.yml`:

```yaml
jentic:
  directory:
    provider: jdbc
    jdbc:
      url: jdbc:postgresql://localhost:5432/mydb
      username: jentic
      password: ${DB_PASSWORD}
```

The `JdbcDirectoryConfiguration` inner class in `JenticAutoConfiguration` activates
automatically when the module is on the classpath.

---

## Schema

Flyway manages all DDL. The initial migration (`V1__create_agent_directory.sql`) creates
two tables: `jentic_agents` and `jentic_agent_capabilities`. Migrations run automatically
on `JdbcAgentDirectory.create()`.

Supported databases: **PostgreSQL** (primary), **MySQL / MariaDB** (optional driver),
**H2** (tests and development).

---

## Tests

```bash
# Unit tests (H2 in-process — no external infrastructure required)
mvn test -pl jentic-adapters-persistence

# Integration tests (Testcontainers PostgreSQL)
mvn verify -pl jentic-adapters-persistence -Dintegration.tests.enabled=true
```

---

## ADRs

- **ADR-022** — `jentic-adapters-persistence` module split (rationale for dedicated module vs `optional=true`)
- **ADR-023** — Persistent agent directory with JDBC (schema design, upsert semantics, presence trade-off)
