# JDBC Agent Directory

Implements all four directory capabilities — `AgentRegistry`, `AgentDiscovery`,
`AgentResolver` and `AgentPresence` — on top of a relational database (PostgreSQL, MySQL,
H2) via plain JDBC. Provides durable agent registration, cross-node capability discovery,
and endpoint resolution across JVM restarts — without requiring additional infrastructure
beyond a JDBC-compatible database.

`JdbcAgentDirectory` is the primary entry point. It owns the HikariCP connection pool,
runs Flyway schema migrations on startup, and exposes the capability implementations.
Presence is **opt-in and separate**: heartbeats are writes, and a JDBC directory should not
start issuing them because you chose durable registration. See
[Presence and heartbeats](#presence-and-heartbeats).

Architectural rationale: ADR-022 (module split), ADR-023 (persistent agent directory),
ADR-028 (agent presence)

---

## Prerequisites

Any JDBC-compatible database. Recommended:

```bash
# PostgreSQL via Docker — recommended for production
docker run -d -p 5432:5432 \
  -e POSTGRES_DB=agenor \
  -e POSTGRES_USER=agenor \
  -e POSTGRES_PASSWORD=agenor_pass \
  postgres:16-alpine

# or with docker compose (if compose.yml is present):
docker compose up postgres
```

For development/testing without external infrastructure, H2 in-process mode is fully
supported (see the example below and `JdbcDirectoryExample` in `agenor-examples`).

---

## Maven dependency (opt-in)

`agenor-adapters-persistence` is a dedicated Maven module per ADR-022 (Optional Adapter
Dependencies Pattern). Consumers that want a JDBC-backed directory must declare it
explicitly:

```xml
<dependency>
    <groupId>dev.agenor</groupId>
    <artifactId>agenor-adapters-persistence</artifactId>
    <version>0.39.0</version>
</dependency>
```

PostgreSQL is the primary database: its JDBC driver and Flyway support come with the module, so
there is nothing else to declare. On MySQL, add the two dependencies in [§ On MySQL](#on-mysql).
For H2, declare `com.h2database:h2` yourself, usually in `test` scope.

Consumers that declare only `agenor-runtime` continue to use `InMemoryAgentDirectory` —
no configuration required, no classpath impact.

### On MySQL

PostgreSQL works with the module alone, and H2 needs only its driver. MySQL needs two optional dependencies, the driver
and Flyway's MySQL support. Without the second one, the first migration fails with
`FlywayException: Unsupported Database: MySQL 8.4`, so neither the directory nor the HITL queue
starts:

```xml
<dependency>
    <groupId>com.mysql</groupId>
    <artifactId>mysql-connector-j</artifactId>
    <version>9.3.0</version>
    <scope>runtime</scope>
</dependency>
<dependency>
    <groupId>org.flywaydb</groupId>
    <artifactId>flyway-mysql</artifactId>
    <!-- with Spring Boot, omit the version: Boot manages the whole Flyway family -->
    <version>10.21.0</version>
    <scope>runtime</scope>
</dependency>
```

`flyway-mysql` must have the same version as the `flyway-core` your build resolves. On its own,
this module brings 10.21.0. Under Spring Boot's dependency management it becomes Boot's version
(11.14.1 with the starter today), which is why the version is omitted there.
`mvn dependency:tree -Dincludes=org.flywaydb` shows the version you actually get.

Tested on MySQL 8.4. MariaDB is covered by the same Flyway module, but this module has not been
tested against it.

---

## Schema

Flyway manages the schema automatically. The first migration
(`V1__create_agent_directory.sql`) creates:

| Table | Primary key | Purpose |
|---|---|---|
| `agenor_agents` | `agent_id` | Stores agent registration, status, endpoint, and metadata |
| `agenor_agent_capabilities` | `(agent_id, capability)` | Normalised capability set; FK cascade-deletes on agent removal |

The migration location defaults to `classpath:db/agenor/directory` and runs
on every `JdbcAgentDirectory.create()` call. It records itself in a history table of its own,
`agenor_directory_schema_history`, and the HITL queue does the same in
`agenor_hitl_schema_history`. Calling it again is idempotent, and the directory can share a
database with the HITL queue and with your own schema, created in any order.

Up to 0.37.0 the location was `classpath:db/migration/agenor-directory`. That is inside Spring
Boot's default Flyway location, which made an application's own Flyway pick Agenor's `V1` up as
its own. The old location still works, with a warning, until 1.0.0.

Flyway's default history table, `flyway_schema_history`, is left to your application. Up to
0.37.0 the directory recorded itself there. A database migrated by 0.37.0 or earlier is picked up
without any action, because the directory finds `agenor_agents` already present. Its old row in
`flyway_schema_history` stays. Delete it by hand if your own migrations use that table.

!!! tip "Your own schema next to the directory"
    Under Spring Boot, `spring.flyway.*` needs nothing for this. The starter runs Agenor's
    migrations after Boot's Flyway, and each keeps its own history. The rest of this tip is for a
    Flyway you configure by hand.

    Give it a history table and a location of its own. Set its baseline below its first version.
    With Flyway's default baseline (1), `baselineOnMigrate` on a database that already holds
    Agenor's tables marks your `V1` as applied and skips it, with no error or warning:

    ```java
    Flyway.configure()
            .dataSource(dataSource)
            .table("myapp_schema_history")
            .locations("classpath:db/migration/myapp")
            .baselineOnMigrate(true)
            .baselineVersion("0")
            .load()
            .migrate();
    ```

---

## Quick start

### Programmatic (any application)

```java
import dev.agenor.adapters.persistence.directory.JdbcAgentDirectory;
import dev.agenor.adapters.persistence.directory.JdbcDirectoryConfig;
import dev.agenor.runtime.AgenorRuntime;

var config = JdbcDirectoryConfig.of(
        "jdbc:postgresql://localhost:5432/agenor",
        "agenor", System.getenv("DB_PASSWORD"));

try (var dir = JdbcAgentDirectory.create(config)) {
    var runtime = AgenorRuntime.builder()
            .agentRegistry(dir.registry())
            .agentDiscovery(dir.discovery())
            .agentResolver(dir.resolver())
            // .agentPresence(dir.presence())  // opt in — see Presence and heartbeats
            .build();
    runtime.start().join();
    // ...
    runtime.stop().join();
}
```

### Spring Boot auto-configuration

Add the dependency and configure `agenor.directory.provider=jdbc`. Provider-specific
properties go in the `properties` map:

```yaml
agenor:
  directory:
    provider: jdbc
    jdbc:
      url: jdbc:postgresql://localhost:5432/agenor
      username: agenor
      password: ${DB_PASSWORD}
      pool-size: 10
    # optional — presence is a separate opt-in
    presence: jdbc
    heartbeat-interval: 30s
```

The auto-configuration activates when `JdbcAgentDirectory` is on the classpath **and**
`provider=jdbc` is set. See `AgenorAutoConfiguration.JdbcDirectoryConfiguration` for
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
| `migrationLocation` | `classpath:db/agenor/directory` | Flyway migration classpath location |

Spring Boot YAML keys (`agenor.directory.jdbc.*`):

| Key | Default | Description |
|---|---|---|
| `agenor.directory.jdbc.url` | — (required when `provider=jdbc`) | JDBC URL |
| `agenor.directory.jdbc.username` | — | Database username |
| `agenor.directory.jdbc.password` | — | Database password |
| `agenor.directory.jdbc.pool-size` | `10` | HikariCP max pool size |

Presence keys (`agenor.directory.*`, all optional):

| Key | Default | Description |
|---|---|---|
| `agenor.directory.presence` | `inmemory` | Set to `jdbc` to store presence in the database |
| `agenor.directory.heartbeat-interval` | — (no heartbeats) | How often the runtime reports its agents alive, e.g. `30s` |
| `agenor.directory.staleness-window` | derived — see below | How long an agent may go unseen before its status reads `UNKNOWN` |

---

## Presence and heartbeats

Presence answers one question: *is this agent still there?* On a single node the in-memory
implementation is enough — it dies with the process, which is exactly when its answers stop
mattering. Across nodes it is not: node B cannot see node A's in-memory map, so it has no
way to tell a running agent from one whose JVM is gone.

`JdbcAgentPresence` puts that answer in the shared table the rest of the directory already
uses. No new table, column or migration: `agenor_agents` already carries `status` and
`last_seen`.

```java
var runtime = AgenorRuntime.builder()
        .agentRegistry(dir.registry())
        .agentDiscovery(dir.discovery())
        .agentResolver(dir.resolver())
        .agentPresence(dir.presence())              // 90-second staleness window
        .heartbeatInterval(Duration.ofSeconds(30))  // required — see below
        .build();
```

```yaml
agenor:
  directory:
    provider: jdbc
    presence: jdbc
    heartbeat-interval: 30s
```

### The two settings, and why they belong together

`heartbeat` writes `UPDATE agenor_agents SET last_seen = ? WHERE agent_id = ?`. `getStatus`
reads `status` and `last_seen` and answers `UNKNOWN` when the row is missing **or** older
than the staleness window. A relational store has no key expiry, so that check happens at
read time and detection is coarse by construction — tens of seconds, not milliseconds.

**Send heartbeats at 15–30 seconds.** Faster buys little: the window is what determines
detection latency, and the table is read on every `sendTo`. Slower risks a window that
cannot absorb one slow write.

**Size the window at about three times the interval**, which tolerates two missed beats.
That is what both defaults do: 30 s → 90 s, the value of
`JdbcAgentPresence.DEFAULT_STALENESS_WINDOW`. In Spring, an unset `staleness-window` is
derived from `heartbeat-interval` the same way.

### The failure mode worth knowing about

A bounded window with nothing heartbeating reports `UNKNOWN` for **every** agent, one window
after start-up, on a perfectly healthy cluster — and nothing in the logs explains it. It
reads like a broken database or a partitioned network.

Two guards:

- In Spring, leaving `heartbeat-interval` unset makes the window unbounded rather than 90
  seconds. Enabling JDBC presence alone then changes *where* presence is stored, not what it
  means: `getStatus` reports whatever the registry last wrote, as the in-memory backend
  always has.
- Programmatically, pass `JdbcAgentPresence.UNBOUNDED_STALENESS_WINDOW` to
  `dir.presence(window)` for the same effect.

### Choosing a different window

```java
dir.presence(Duration.ofMinutes(2))                          // custom
dir.presence(JdbcAgentPresence.UNBOUNDED_STALENESS_WINDOW)   // never expire
```

Unlike its siblings, `presence(Duration)` returns a new instance per call — the window is a
property of the view, not of the connection pool, and the instances are stateless and share
the pool. `presence()` with no argument is cached and uses the default window.

### Clock skew

`last_seen` is written by the beating node and compared against the reading node's clock, so
the answer is only as good as the agreement between them. Ordinary NTP drift is well inside
a 90-second window. A `last_seen` in the future reads as fresh rather than stale, so a fast
clock costs visibility, never a false liveness claim.

---

## Mixed backend: JDBC directory + in-memory presence

Presence can stay in memory while everything else is durable. This is the default when
`.agentPresence()` is not called, and remains a reasonable choice for a single node or when
the heartbeat write volume is unwelcome: at 100 agents on a 30-second interval that is 200
writes per minute against a table also read on every `sendTo`.

```java
var runtime = AgenorRuntime.builder()
        .agentRegistry(dir.registry())
        .agentDiscovery(dir.discovery())
        .agentResolver(dir.resolver())
        // No .agentPresence() call → runtime fills in InMemoryAgentDirectory
        .build();
```

The `AgenorRuntime.Builder` assembles a `CompositeAgentDirectory` from the three JDBC
capabilities plus the default in-memory `AgentPresence`. The result satisfies the full
`AgentDirectory` interface without any changes to agent code.

What you give up is the cross-node answer: each node sees only the agents it started, and
`getStatus` on a peer's agent returns `UNKNOWN`. A Redis TTL-based presence, which pushes
expiry into the store instead of checking it at read time, is designed in ADR-028 Phase B
and not yet built.

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

Testcontainers-based PostgreSQL integration tests are in `JdbcAgentDirectoryIT` and
`JdbcAgentPresenceIT` — the latter runs two nodes with separate connection pools against one
database, which is where presence either works or does not:

```bash
mvn verify -pl agenor-adapters-persistence -Dintegration.tests.enabled=true
```

Unit tests against an embedded H2 database run as part of `mvn test` (no flag required):

```bash
mvn test -pl agenor-adapters-persistence
```
