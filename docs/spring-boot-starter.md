# Spring Boot Starter

`jentic-spring-boot-starter` provides zero-configuration auto-wiring of `JenticRuntime`
into any Spring Boot 4.0.x application. Add one dependency, configure `application.yml`,
and Jentic starts with the Spring context.

## Dependency

```xml
<dependency>
    <groupId>dev.agenor</groupId>
    <artifactId>jentic-spring-boot-starter</artifactId>
    <version>0.24.0-SNAPSHOT</version>
</dependency>
```

All Spring Boot dependencies are declared `optional=true` — they do not appear on the
transitive classpath of non-Spring consumers.

## Quick Start

`application.yml`:
```yaml
jentic:
  agents:
    base-package: com.example.agents
```

That is all. `JenticRuntime` is created, started, and stopped automatically by the
Spring lifecycle. Agents in the configured package are discovered and registered at startup.

## Configuration Reference

All keys are under the `jentic` prefix. Every key is optional and falls back to a default.

### `jentic.runtime`

| Key | Default | Description |
|-----|---------|-------------|
| `name` | `jentic-runtime` | Runtime instance name |
| `environment` | `development` | Environment label (`development`, `staging`, `production`, `test`) |
| `properties` | `{}` | Arbitrary key/value pairs forwarded to `RuntimeConfig` |

### `jentic.agents`

| Key | Default | Description |
|-----|---------|-------------|
| `auto-discovery` | `true` | Discover `@JenticAgent` classes at startup |
| `base-package` | — | Root package to scan |
| `scan-packages` | `[]` | Additional packages to scan |
| `scan-paths` | `[]` | Legacy alias for `scan-packages` (kept for compatibility with `jentic.yml`) |

`base-package` and `scan-packages` are merged — both are scanned.

### `jentic.scheduler`

| Key | Default | Description |
|-----|---------|-------------|
| `provider` | `simple` | Scheduler implementation |
| `thread-pool-size` | `10` | Thread pool size |

### `jentic.messaging`

| Key | Default | Description |
|-----|---------|-------------|
| `provider` | `inmemory` | Implementation: `inmemory`, `redis` |
| `redis.uri` | `redis://localhost:6379` | Redis URI (only when `provider=redis`) |
| `redis.consumer-group-prefix` | `jentic` | Prefix for stream keys and consumer groups |
| `redis.read-block-timeout-ms` | `2000` | XREADGROUP BLOCK timeout (ms) |
| `redis.max-stream-length` | `100000` | Max entries per stream before trimming |
| `redis.pending-entries-timeout-ms` | `30000` | Idle time before redelivery of pending entries (ms) |
| `redis.max-delivery-attempts` | `3` | Attempts before moving to dead-letter |

The `redis.*` sub-section is only read when `provider=redis` and `jentic-adapters` is on the classpath.
`@ConditionalOnMissingBean` allows providing a custom `RedisMessagingFactory` bean to override all defaults.

Redis example:
```yaml
jentic:
  messaging:
    provider: redis
    redis:
      uri: redis://localhost:6379
      consumer-group-prefix: my-app
```

### `jentic.directory`

| Key | Default | Description |
|-----|---------|-------------|
| `provider` | `local` | Implementation: `local`, `inmemory`, `jdbc` |
| `jdbc.url` | — | JDBC connection URL (required when `provider=jdbc`) |
| `jdbc.username` | `""` | Database username |
| `jdbc.password` | `""` | Database password |
| `jdbc.pool-size` | `10` | HikariCP connection pool size |

The `jdbc.*` sub-section is only read when `provider=jdbc` and `jentic-adapters-persistence` is on the classpath.
Flyway schema migration runs automatically on startup.

JDBC example:
```yaml
jentic:
  directory:
    provider: jdbc
    jdbc:
      url: jdbc:postgresql://localhost:5432/mydb
      username: jentic
      password: ${DB_PASSWORD}
```

### `jentic.llm`

| Key | Default | Description |
|-----|---------|-------------|
| `provider` | `none` | LLM provider (`none`, `openai`, `anthropic`, `ollama`) |
| `api-key` | — | API key (required for `openai` and `anthropic`) |
| `model` | provider default | Model name |
| `base-url` | `http://localhost:11434` | Base URL (used by `ollama` only) |

When `provider=none` (default), no `LLMProvider` bean is created. When a non-`none` provider
is set, `jentic-adapters` must be on the classpath — the starter will fail fast with a clear
error message otherwise.

Provider defaults:

| Provider | Default model |
|----------|--------------|
| `openai` | `gpt-4o-mini` |
| `anthropic` | `claude-3-haiku-20240307` |
| `ollama` | `llama3.2` |

## LLM Provider Bean and Agent Injection

When `jentic.llm.provider` is set, the auto-configuration creates a `LLMProvider` bean and
registers it with the runtime. Agents that declare a `LLMProvider` constructor parameter
receive it automatically via `AgentFactory` constructor injection:

```java
@JenticAgent("my-llm-agent")
public class MyLlmAgent extends BaseAgent {

    private final LLMProvider provider;

    // AgentFactory injects the configured LLMProvider automatically
    public MyLlmAgent(LLMProvider provider) {
        this.provider = provider;
    }

    @JenticBehavior(type = JenticBehaviorType.CYCLIC, interval = "30s")
    public void analyze() {
        LLMRequest req = LLMRequest.builder(provider.getProviderName())
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
    "jentic": {
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
public class MyJenticConfig {

    // Overrides the auto-configured JenticRuntime
    @Bean(name = "jenticRuntime")
    public JenticRuntime jenticRuntime() {
        return JenticRuntime.builder()
                .fromClasspathConfig("my-jentic.yml")   // use a custom YAML file
                .build();
    }

    // Must also provide the lifecycle bean when overriding the runtime
    @Bean
    public SmartLifecycle jenticRuntimeLifecycle(JenticRuntime jenticRuntime) {
        return new SmartLifecycle() {
            private volatile boolean running = false;
            public void start() { jenticRuntime.start().join(); running = true; }
            public void stop()  { jenticRuntime.stop().join();  running = false; }
            public boolean isRunning() { return running; }
        };
    }
}
```

## Relationship with `jentic.yml`

The starter configures the runtime exclusively from `application.yml` via
`@ConfigurationProperties`. It does **not** load `jentic.yml` from the classpath.

If you need to load from a `jentic.yml` file, declare your own `JenticRuntime` bean
(which suppresses auto-configuration via `@ConditionalOnMissingBean`):

```java
@Bean
public JenticRuntime jenticRuntime() {
    return JenticRuntime.builder()
            .fromClasspathConfig("jentic.yml")
            .build();
}
```

## Spring Boot 4.x

The starter targets Spring Boot **4.0.x** (adopted in 0.18.0, see `ADR-016`).
The auto-configuration API (`AutoConfiguration.imports`, `@ConditionalOnMissingBean`, etc.)
is stable across Spring Boot 4.x versions.

## See Also

- [Configuration Guide](configuration.md) — native `jentic.yml` format and `JenticRuntime` builder
- [Agent Development Guide](agent-development.md) — `@JenticAgent`, behaviors, lifecycle
- [LLM Integration Guide](llm-integration.md) — `LLMProvider`, `LLMAgent`, providers
- [Architecture Guide](architecture.md) — module overview
