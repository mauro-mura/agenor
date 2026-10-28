# Spring Boot Starter

`agenor-spring-boot-starter` provides zero-configuration auto-wiring of `AgenorRuntime`
into any Spring Boot 4.0.x application. Add one dependency, configure `application.yml`,
and Agenor starts with the Spring context.

## Dependency

```xml
<dependency>
    <groupId>dev.agenor</groupId>
    <artifactId>agenor-spring-boot-starter</artifactId>
    <version>0.39.0</version>
</dependency>
```

All Spring Boot dependencies are declared `optional=true` — they do not appear on the
transitive classpath of non-Spring consumers. `agenor-runtime-scanning` is **not** optional:
it is what makes `agenor.agents.base-package` below work, and it is a small module with no
third-party dependencies of its own (see [`agenor-runtime-scanning`](https://github.com/mauro-mura/agenor/blob/main/README.md#agenor-runtime-scanning)
in the README). A consumer building for GraalVM native-image
who does not use classpath discovery can exclude it:

```xml
<dependency>
    <groupId>dev.agenor</groupId>
    <artifactId>agenor-spring-boot-starter</artifactId>
    <version>0.39.0</version>
    <exclusions>
        <exclusion>
            <groupId>dev.agenor</groupId>
            <artifactId>agenor-runtime-scanning</artifactId>
        </exclusion>
    </exclusions>
</dependency>
```

Setting `base-package`, `scan-packages`, or `scan-paths` with that exclusion in place fails
fast at startup with a message naming the dependency to add back.

## Quick Start

`application.yml`:
```yaml
agenor:
  agents:
    base-package: com.example.agents
```

That is all. `AgenorRuntime` is created, started, and stopped automatically by the
Spring lifecycle. Agents in the configured package are discovered and registered at startup.

## Configuration Reference

All keys are under the `agenor` prefix. Every key is optional and falls back to a default.

### `agenor.runtime`

| Key | Default | Description |
|-----|---------|-------------|
| `name` | `agenor-runtime` | Runtime instance name |
| `environment` | `development` | Environment label (`development`, `staging`, `production`, `test`) |
| `properties` | `{}` | Arbitrary key/value pairs forwarded to `RuntimeConfig` |

### `agenor.agents`

| Key | Default | Description |
|-----|---------|-------------|
| `auto-discovery` | `true` | Discover `@Agent` classes at startup |
| `base-package` | — | Root package to scan |
| `scan-packages` | `[]` | Additional packages to scan |
| `scan-paths` | `[]` | Legacy alias for `scan-packages` (kept for compatibility with `agenor.yml`) |

`base-package` and `scan-packages` are merged — both are scanned. This is the one part of
the starter that is not optional at the dependency level — see the exclusion note above.

### `agenor.scheduler`

| Key | Default | Description |
|-----|---------|-------------|
| `provider` | `simple` | Scheduler implementation |
| `thread-pool-size` | `10` | Thread pool size |

### `agenor.messaging`

| Key | Default | Description |
|-----|---------|-------------|
| `provider` | `inmemory` | Implementation: `inmemory`, `redis` |
| `redis.uri` | `redis://localhost:6379` | Redis URI (only when `provider=redis`) |
| `redis.consumer-group-prefix` | `agenor` | Prefix for stream keys and consumer groups |
| `redis.read-block-timeout-ms` | `2000` | XREADGROUP BLOCK timeout (ms) |
| `redis.max-stream-length` | `100000` | Max entries per stream before trimming |
| `redis.pending-entries-timeout-ms` | `30000` | Idle time before redelivery of pending entries (ms) |
| `redis.max-delivery-attempts` | `3` | Attempts before moving to dead-letter |

The `redis.*` sub-section is only read when `provider=redis`. That setting needs `agenor-adapters`
and `lettuce-core` on the classpath, and startup fails naming the missing artifact if one is
absent. The same holds for `agenor.directory.provider=jdbc` and `agenor.hitl.provider=jdbc`
(`agenor-adapters-persistence`), `agenor.llm.provider` and `agenor.telemetry.exporter`
(`agenor-adapters`). Up to 0.37.0 only the LLM provider failed; the others fell back to their
in-memory default with no error.
`@ConditionalOnMissingBean` allows providing a custom `RedisMessagingFactory` bean to override all defaults.

**Dead letters exist on both providers, and only their reach differs.** The two `redis.*` keys
above govern *redelivery* — how many times a message is retried and how long an unacknowledged
one waits — not whether a message the framework gives up on is recorded. With
`provider=inmemory` there is no redelivery, so a failing handler dead-letters on the first
attempt into a bounded in-memory buffer that forgets on restart; with `provider=redis` it
dead-letters after `redis.max-delivery-attempts` into a durable stream. Either way
`runtime.getDeadLetterQueue()` answers, and the console's `GET /api/deadletters` shows it. See
[Messaging](messaging.md#when-delivery-fails-for-good).

Redis example:
```yaml
agenor:
  messaging:
    provider: redis
    redis:
      uri: redis://localhost:6379
      consumer-group-prefix: my-app
```

### `agenor.directory`

| Key | Default | Description |
|-----|---------|-------------|
| `provider` | `local` | Implementation: `local`, `inmemory`, `jdbc` |
| `jdbc.url` | — | JDBC connection URL (required when `provider=jdbc`) |
| `jdbc.username` | `""` | Database username |
| `jdbc.password` | `""` | Database password |
| `jdbc.pool-size` | `10` | HikariCP connection pool size |

The `jdbc.*` sub-section is only read when `provider=jdbc` and `agenor-adapters-persistence` is on the classpath.
Flyway schema migration runs automatically on startup.

Your application's own Flyway (`spring-boot-starter-flyway`) can use the same database with
Boot's defaults. Agenor's migrations are outside `db/migration` and run after Boot's Flyway, and
each schema keeps its own history table, so `spring.flyway.*` needs no setting for Agenor.

JDBC example:
```yaml
agenor:
  directory:
    provider: jdbc
    jdbc:
      url: jdbc:postgresql://localhost:5432/mydb
      username: agenor
      password: ${DB_PASSWORD}
```

### More than one node

A deployment across several JVMs needs both backends: Redis to carry messages between nodes,
and a shared JDBC directory so that each node can find which node owns an agent. Set both
providers; the starter builds one runtime with both:

```yaml
agenor:
  messaging:
    provider: redis
    redis:
      uri: redis://localhost:6379
  directory:
    provider: jdbc
    jdbc:
      url: jdbc:postgresql://localhost:5432/mydb
      username: agenor
      password: ${DB_PASSWORD}
```

Every node uses the same Redis URI, the same JDBC URL and the same `consumer-group-prefix`.
The starter generates a node id per JVM, and there is no property to set it. Each agent is
advertised in `agenor_agents` under that id with `endpoint_transport_type = redis`. A row with a
blank `node_id` or transport `local` means messages to that agent from another node are lost
without an error. [Distributed quick start](distributed-quick-start.md) shows the same setup
built by hand, with a fixed node id per node.

### `agenor.llm`

| Key | Default | Description |
|-----|---------|-------------|
| `provider` | `none` | LLM provider (`none`, `openai`, `anthropic`, `ollama`) |
| `api-key` | — | API key (required for `openai` and `anthropic`) |
| `model` | provider default | Model name |
| `base-url` | `http://localhost:11434` | Base URL (used by `ollama` only) |

When `provider=none` (default), no `LLMProvider` bean is created. When a non-`none` provider
is set, `agenor-adapters` must be on the classpath — the starter will fail fast with a clear
error message otherwise.

Provider defaults:

| Provider | Default model |
|----------|--------------|
| `openai` | `gpt-4o-mini` |
| `anthropic` | `claude-haiku-4-5-20251001` |
| `ollama` | `llama3.2` |

## LLM Provider Bean and Agent Injection

When `agenor.llm.provider` is set, the auto-configuration creates a `LLMProvider` bean and
registers it with the runtime. Agents that declare a `LLMProvider` constructor parameter
receive it automatically via `AgentFactory` constructor injection:

```java
@Agent("my-llm-agent")
public class MyLlmAgent extends BaseAgent {

    private final LLMProvider provider;

    // AgentFactory injects the configured LLMProvider automatically
    public MyLlmAgent(LLMProvider provider) {
        this.provider = provider;
    }

    @Behavior(type = BehaviorType.CYCLIC, interval = "30s")
    public void analyze() {
        LLMRequest req = LLMRequest.builder()
                .userMessage("Summarize the current system status.")
                .maxTokens(200)
                .build();
        log.info("LLM: {}", provider.chat(req).join().content());
    }
}
```

For agents that should work both with and without LLM, provide a no-arg fallback constructor:

```java
public MyLlmAgent(LLMProvider provider) { this.provider = provider; }
public MyLlmAgent()                     { this.provider = null; }   // fallback
```

`AgentFactory` prefers the most-parameter constructor. If `LLMProvider` is not available,
it falls back to the no-arg constructor.

### Giving an agent anything else

A discovered agent's constructor can receive the `LLMProvider` and the runtime's own services
(`MessageDispatcher`, `AgentDirectory`, `BehaviorScheduler`, `MemoryStore`, `AgentContext`), and
nothing else. The `ApprovalGate` bean is wired into the runtime for `@RequiresApproval`, not
into constructors, and the application's own beans are not seen at all.

For an agent that needs either, leave it out of `agenor.agents.base-package`, build it as a
Spring bean, and register it with the runtime. The starter starts the runtime after the
context's beans are created, so an agent registered from a `@Bean` method starts with the
others:

```java
@Configuration
class AgentConfig {

    @Bean
    ReviewAgent reviewAgent(AgenorRuntime runtime, ApprovalGate approvals, TicketStore tickets) {
        var agent = new ReviewAgent(approvals, tickets);
        runtime.registerAgent(agent);
        return agent;
    }
}
```

## Lifecycle

The starter uses `SmartLifecycle` with `phase = Integer.MAX_VALUE - 1`. This means:

- **Start**: fires after all infrastructure beans (data sources, messaging, etc.) — blocks until all agents are running
- **Stop**: fires before infrastructure beans — blocks until all agents are stopped

This guarantees a clean startup/shutdown sequence and that the health endpoint reflects the
true state of the runtime.

## Actuator Health

When `spring-boot-starter-actuator` is on the classpath, the starter registers a
`HealthIndicator` automatically:

```bash
curl http://localhost:8080/actuator/health
```

```json
{
  "status": "UP",
  "components": {
    "agenor": {
      "status": "UP",
      "details": {
        "runtime.name": "my-system",
        "agents.total": 3,
        "agents.running": 3
      }
    }
  }
}
```

## User Bean Override

Every auto-configured bean is guarded by `@ConditionalOnMissingBean`. Declare your own bean
to override any default:

```java
@Configuration
public class MyAgenorConfig {

    // Overrides the auto-configured AgenorRuntime
    @Bean(name = "agenorRuntime")
    public AgenorRuntime agenorRuntime() {
        return AgenorRuntime.builder()
                .fromClasspathConfig("my-agenor.yml")   // use a custom YAML file
                .build();
    }
}
```

Do not declare a lifecycle bean next to it. The starter's `AgenorRuntimeLifecycle` starts and
stops whichever `AgenorRuntime` is in the context, yours included. A second `SmartLifecycle` that
calls `start()` only starts the runtime twice: the second call returns at once, but the runtime
is no longer started from one place. Override `AgenorRuntimeLifecycle` by that bean name only to
change how the runtime starts, not to make it start.

## Relationship with `agenor.yml`

The starter configures the runtime exclusively from `application.yml` via
`@ConfigurationProperties`. It does **not** load `agenor.yml` from the classpath.

If you need to load from a `agenor.yml` file, declare your own `AgenorRuntime` bean
(which suppresses auto-configuration via `@ConditionalOnMissingBean`):

```java
@Bean
public AgenorRuntime agenorRuntime() {
    return AgenorRuntime.builder()
            .fromClasspathConfig("agenor.yml")
            .build();
}
```

## Spring Boot 4.x

The starter targets Spring Boot **4.0.x** (adopted in 0.18.0, see `ADR-016`).
The auto-configuration API (`AutoConfiguration.imports`, `@ConditionalOnMissingBean`, etc.)
is stable across Spring Boot 4.x versions.

### Versions Spring Boot replaces

An application whose parent is `spring-boot-starter-parent` gets Spring Boot's versions of the
libraries both manage, not Agenor's. Up to Boot 4.0.8, two of them are below what Agenor is
built against, and need setting back:

```xml
<properties>
    <!-- Boot 4.0.x manages 2.21.x; LangChain4j in agenor-adapters needs 2.22 -->
    <jackson-2-bom.version>2.22.1</jackson-2-bom.version>
    <!-- Only with Redis messaging. Boot 4.0.x manages 6.8.2 -->
    <lettuce.version>7.5.1.RELEASE</lettuce.version>
</properties>
```

Flyway and HikariCP go the other way: Boot manages a major version above the persistence
module's (Flyway 11, HikariCP 7). Leave them to Boot: the starter's own tests run on Boot's
versions.

## See Also

- [Configuration Guide](configuration.md) — native `agenor.yml` format and `AgenorRuntime` builder
- [Agent Development Guide](agent-development.md) — `@Agent`, behaviors, lifecycle
- [LLM Integration Guide](llm-integration.md) — `LLMProvider`, `LLMAgent`, providers
- [Architecture Guide](architecture.md) — module overview
