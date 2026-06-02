# Configuration Guide

> **This guide covers the native `agenor.yml` format** loaded by `DefaultConfigurationLoader`.
> If you are using the Spring Boot starter, see [Spring Boot Starter](spring-boot-starter.md) —
> the `application.yml` format differs in structure for provider-specific sections.

Agenor supports configuration via YAML files and programmatic builders. This page documents the current configuration keys and formats.

## Loading Configuration

`ConfigurationLoader` is an interface in `agenor-core`. The default implementation is `DefaultConfigurationLoader` in `agenor-runtime`. The recommended way to load configuration is via the `AgenorRuntime` builder:

```java
import dev.agenor.runtime.AgenorRuntime;

// Load from a filesystem path (throws ConfigurationException on invalid config)
var runtime = AgenorRuntime.builder()
        .fromFilesystemConfig("./agenor.yml")
        .build();

        // Load from classpath resource (throws ConfigurationException on invalid config)
        var runtime = AgenorRuntime.builder()
                .fromClasspathConfig("agenor-test.yml")
                .build();

        // Load default (see discovery order below)
        var runtime = AgenorRuntime.builder()
                .withDefaultConfig()
                .build();

        // Provide a pre-built configuration object
        AgenorConfiguration config = AgenorConfiguration.defaults();
        var runtime = AgenorRuntime.builder()
                .withConfiguration(config)
                .build();

// withConfiguration validates the provided config and throws ConfigurationException
// if it is null or fails validation (e.g. blank runtime.name).
```

If none of the config builder methods are called, `AgenorRuntime` starts with built-in defaults.
**Note:** All builder config methods throw `ConfigurationException` (unchecked) if the loaded or provided configuration fails validation. No checked exception handling is required at the call site.

### Direct loader usage

If you need the `AgenorConfiguration` object without building a runtime:

```java
import dev.agenor.runtime.config.DefaultConfigurationLoader;

var loader = new DefaultConfigurationLoader();
var config = loader.loadDefault();
// or
var config = loader.loadFromFile("./agenor.yml");
// or
var config = loader.loadFromClasspath("agenor-test.yml");
```

### Default discovery order (`loadDefault`)

1. `agenor.yml` in the current working directory (filesystem)
2. `agenor.yml` on the classpath

Built-in defaults are used if neither is found.

### Environment variable substitution

`${ENV_VAR}` syntax is supported inside YAML values.

---

## YAML Structure

The root element is `agenor:`. All sub-sections are optional and fall back to defaults if omitted.

```yaml
agenor:
  runtime:
    name: my-agent-system          # default: agenor-runtime
    environment: production        # default: development
    properties:                    # optional arbitrary key/value pairs
      custom-key: custom-value

  agents:
    autoDiscovery: true            # default: true
    basePackage: "dev.example"     # single package (added to scan list)
    scanPackages:                  # list of packages to scan
      - "dev.example.agents"
      - "com.other.agents"
    scanPaths:                     # legacy alias, merged with scanPackages
      - "dev.example.legacy"

  messaging:
    provider: inmemory             # default: inmemory
    properties: {}                 # optional provider-specific properties

  directory:
    provider: local                # default: local
    properties: {}                 # optional provider-specific properties

  scheduler:
    provider: simple               # default: simple
    threadPoolSize: 10             # default: 10
    properties: {}                 # optional provider-specific properties
```

Notes:
- Keys map to `dev.agenor.core.AgenorConfiguration` via `dev.agenor.runtime.config.AgenorConfigurationWrapper`.
- `basePackage` and `scanPaths` are merged into `scanPackages` at load time.
- Unknown keys are ignored by the loader.

---

## Provider Reference

### Messaging providers

| Value | Module required | Notes |
|---|---|---|
| `inmemory` | `agenor-runtime` (built-in) | Default; single JVM only |
| `redis` | `agenor-adapters` | Durable, multi-node; requires Lettuce on classpath |

#### Redis messaging (`provider: redis`)

Provider-specific keys go inside `messaging.properties` as string values:

```yaml
agenor:
  messaging:
    provider: redis
    properties:
      uri: redis://localhost:6379          # default: redis://localhost:6379
      consumer-group-prefix: agenor        # default: agenor
      read-block-timeout-ms: "2000"        # default: 2000
      max-stream-length: "100000"          # default: 100000
      pending-entries-timeout-ms: "30000"  # default: 30000
      max-delivery-attempts: "3"           # default: 3
```

> **Note:** Setting `provider: redis` in `agenor.yml` only records the provider name and properties
> in `AgenorConfiguration`. The actual `RedisMessagingFactory` must be wired manually
> (see `agenor-adapters` documentation) or automatically via the **Spring Boot starter**,
> which reads these keys and creates the adapter bean.
> See [Spring Boot Starter](spring-boot-starter.md) for zero-wiring Redis setup.

---

### Directory providers

| Value | Module required | Notes |
|---|---|---|
| `local` | `agenor-runtime` (built-in) | Default; single JVM, survives restarts via in-memory state |
| `inmemory` | `agenor-runtime` (built-in) | Alias for `local` |
| `jdbc` | `agenor-adapters-persistence` | Durable, multi-node; requires a JDBC driver on classpath |

#### JDBC directory (`provider: jdbc`)

Provider-specific keys go inside `directory.properties` as string values:

```yaml
agenor:
  directory:
    provider: jdbc
    properties:
      url: jdbc:postgresql://localhost:5432/agenor   # required
      username: agenor                               # optional
      password: ${DB_PASSWORD}                       # optional
      pool-size: "10"                               # default: 10; must be a string
```

> **Note:** In a non-Spring-Boot context, `provider: jdbc` only records the intent in
> `AgenorConfiguration`. `JdbcAgentDirectory` must be instantiated and passed to the runtime
> manually. The **Spring Boot starter** handles this automatically when
> `agenor-adapters-persistence` is on the classpath.
> See [JDBC Agent Directory](adapters/jdbc-directory.md) and [Spring Boot Starter](spring-boot-starter.md).

Supported JDBC URLs: `jdbc:postgresql://…`, `jdbc:mysql://…`, `jdbc:h2:…` (H2 for dev/test).

---

## Programmatic Configuration

You can configure the runtime entirely in code without a YAML file:

```java
import dev.agenor.runtime.AgenorRuntime;

var runtime = AgenorRuntime.builder()
        .scanPackage("dev.example.agents")   // add one package
        .scanPackages("dev.example.other")   // varargs variant
        .build();

runtime.

start();
```

For full control over configuration values:

```java
import dev.agenor.core.AgenorConfiguration;

var config = new AgenorConfiguration(
        new AgenorConfiguration.RuntimeConfig("my-system", "production", null),
        new AgenorConfiguration.AgentsConfig(true, null, null, List.of("dev.example"), null),
        AgenorConfiguration.MessagingConfig.defaults(),
        AgenorConfiguration.DirectoryConfig.defaults(),
        AgenorConfiguration.SchedulerConfig.defaults()
);

var runtime = AgenorRuntime.builder()
        .withConfiguration(config)
        .build();
```

---

## Persistence

`agenor-runtime` provides a file-based persistence service suitable for development/testing. Persistence is enabled programmatically via `PersistenceManager`.

---

## Logging

Logging uses SLF4J. In tests/examples, Logback is included; provide your own `logback.xml` or `logback-test.xml` as needed.

---

## Example File

See `agenor-runtime/src/test/resources/agenor-test.yml` for a working example.
