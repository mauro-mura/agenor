# ADR-022: `agenor-adapters-persistence` Module Split

**Status**: Accepted  
**Date**: 2026-05-17  
**Authors**: Project Team  
**References**: ADR-002 (Interface-First Architecture), ADR-003 (Maven Multi-Module Structure),
ADR-004 (Progressive Complexity), ADR-018 (Optional Adapter Dependencies Pattern),
ADR-020 (Core API Refactor for Distributed Backends)

---

## Context

Introducing a persistent agent directory backed by a relational database
(Postgres primary target; MySQL, MariaDB, H2 also supported) requires:

- **HikariCP** — connection pooling
- **Flyway** — schema versioning and migration management
- **JDBC drivers** — `org.postgresql:postgresql` and optionally MySQL/MariaDB drivers at
  runtime scope
- **Schema migration files** bundled as classpath resources

ADR-018 establishes the placement rule for new adapters:

| Condition | Placement |
|-----------|-----------|
| Lightweight library | `agenor-adapters`, `compile` scope |
| Heavy but universally useful (OTel, Lettuce) | `agenor-adapters`, `optional=true` |
| Heavyweight infrastructure, mutually exclusive alternatives | Dedicated sub-module |
| Commercial / Enterprise | Separate Enterprise module |

The JDBC persistence stack falls unambiguously into the third row:

1. **Heavyweight**: HikariCP + Flyway + JDBC drivers add significant classpath weight.
2. **Mutually exclusive**: a deployment uses exactly one directory backend. Consul, etcd, and
   DynamoDB (all deferred to Enterprise) are mutually exclusive with JDBC. Having them share a
   module would force all alternatives onto every consumer's classpath.
3. **Operationally distinct**: Flyway performs DDL at startup; HikariCP manages a connection
   pool with health-check threads. These are infrastructure concerns orthogonal to the agentic
   toolkit (`LLMProvider`, MCP, A2A) that `agenor-adapters` delivers.
4. **`optional=true` is insufficient**: marking Flyway `optional=true` would still bundle
   migration SQL files as classpath resources, leave HikariCP startup hooks reachable, and
   make classpath isolation impossible to verify cleanly.

A dedicated module makes the opt-in explicit at the Maven dependency level, consistent with
ADR-004 (progressive complexity: every heavier capability is a separate artifact consumers
declare by choice).

---

## Decision

Introduce a new Maven module **`agenor-adapters-persistence`** under the parent `jentic`
reactor, positioned after `agenor-adapters` in the `<modules>` list.

**Scope of this module at 0.22.0:**

- `AgentRegistry`, `AgentDiscovery`, `AgentResolver` implementations over JDBC
- Flyway-managed schema for the agent directory tables
- `AgentPresence` intentionally **not** implemented (see Consequences)

**Scope extended in a subsequent release:**

- `ApprovalGate` persistent implementation (`JdbcApprovalGate`)
- Additional Flyway migration for the HITL table

No new Maven module is needed for persistent HITL; the persistence concern is already isolated here.

### Module coordinates

```xml
<groupId>dev.agenor</groupId>
<artifactId>agenor-adapters-persistence</artifactId>
<version>${project.version}</version>
```

Added to `agenor-bom/pom.xml` `<dependencyManagement>` so consumers get version management
without specifying it explicitly.

### Package root

`dev.agenor.adapters.persistence`

### `Automatic-Module-Name`

`dev.agenor.adapters.persistence`

---

## Implementation plan

### Maven structure

```
jentic/
├── pom.xml                          ← add agenor-adapters-persistence to <modules>
├── agenor-bom/
│   └── pom.xml                      ← add artifact to <dependencyManagement>
└── agenor-adapters-persistence/
    ├── pom.xml
    └── src/
        ├── main/
        │   ├── java/dev/jentic/adapters/persistence/
        │   │   ├── directory/
        │   │   │   ├── JdbcAgentRegistry.java
        │   │   │   ├── JdbcAgentDiscovery.java
        │   │   │   ├── JdbcAgentResolver.java
        │   │   │   ├── JdbcDirectoryConfig.java
        │   │   │   └── DirectorySchemaManager.java
        │   │   └── JdbcHelper.java
        │   └── resources/db/migration/agenor-directory/
        │       └── V1__create_agent_directory.sql
        └── test/
            └── java/dev/jentic/adapters/persistence/
                ├── directory/
                │   ├── JdbcAgentRegistryTest.java
                │   └── JdbcAgentDirectoryIT.java
                └── contract/        ← reuse contract suites from ADR-020
```

### Dependencies in `agenor-adapters-persistence/pom.xml`

| Dependency | Scope | Rationale |
|------------|-------|-----------|
| `agenor-core` | `compile` | Interfaces being implemented |
| `com.zaxxer:HikariCP` | `compile` | Connection pooling |
| `org.flywaydb:flyway-core` | `compile` | Schema migration |
| `org.postgresql:postgresql` | `runtime` | Primary driver; not required at compile time |
| `com.mysql:mysql-connector-j` | `runtime`, `optional=true` | MySQL/MariaDB; opt-in |
| `com.h2database:h2` | `test` | Fast in-process DB for unit tests |
| `org.testcontainers:postgresql` | `test` | Integration tests |

### Spring Boot starter integration

```java
@ConditionalOnClass(JdbcAgentRegistry.class)
@ConditionalOnProperty(prefix = "jentic.directory", name = "registry", havingValue = "jdbc")
class JdbcDirectoryAutoConfiguration { ... }
```

YAML:

```yaml
jentic:
  directory:
    registry:  jdbc        # "in-memory" (default) | "jdbc"
    discovery: jdbc
    resolver:  jdbc
    presence:  in-memory   # AgentPresence not implemented by this module
```

---

## Consequences

**Positive:**

- `agenor-adapters` transitive classpath is not widened by HikariCP, Flyway, or JDBC drivers;
  verified with `mvn dependency:tree`.
- Consumers pay the JDBC stack only when they declare `agenor-adapters-persistence`
  explicitly, consistent with ADR-004.
- The module boundary makes the capability split (ADR-020) tangible: JDBC implements exactly
  `AgentRegistry`, `AgentDiscovery`, `AgentResolver` — the interfaces that map to relational
  access patterns.
- Persistent HITL support extends this module without requiring a new artifact; the
  infrastructure investment is amortised.

**Negative / trade-offs:**

- `AgentPresence` is not implemented by this module. Operators who want multi-node liveness
  must combine this module with an in-memory presence backend (single-node only) or wait for
  a future dedicated presence backend (Redis TTL or Consul — Enterprise tier). This is
  documented explicitly; attempting to configure `jentic.directory.presence: jdbc` yields a
  clear startup error.
- An additional module increases the Maven reactor build time marginally.

---

## Alternatives Considered

**Keep everything in `agenor-adapters` with `optional=true`.**  
Rejected per ADR-018: Flyway would still bundle migration SQL files on the default classpath;
HikariCP startup hooks would be reachable regardless of the `optional` flag at the Maven
level. Classpath isolation cannot be verified reliably. The `optional=true` pattern is
appropriate for single-purpose libraries (OTel SDK, Lettuce) where no side-effectful
startup code runs.

---

## Related ADRs

- ADR-002: Interface-first architecture — module implements only the capability subset it supports
- ADR-003: Maven Multi-Module Structure — updated to include `agenor-adapters-persistence`
- ADR-004: Progressive complexity — heavier backend is an explicit opt-in artifact
- ADR-018: Optional adapter dependencies pattern — this module is the "dedicated sub-module" case
- ADR-020: Core API refactor — `AgentRegistry`, `AgentDiscovery`, `AgentResolver` are the
  interfaces this module implements; `AgentPresence` is deliberately omitted
