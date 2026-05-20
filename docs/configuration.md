# Configuration Guide

> **This guide covers the native `jentic.yml` format** loaded by `DefaultConfigurationLoader`.
> If you are using the Spring Boot starter, see [Spring Boot Starter](spring-boot-starter.md) —
> the `application.yml` format differs in structure for provider-specific sections.

Jentic supports configuration via YAML files and programmatic builders. This page documents the current configuration keys and formats.

## Loading Configuration

`ConfigurationLoader` is an interface in `jentic-core`. The default implementation is `DefaultConfigurationLoader` in `jentic-runtime`. The recommended way to load configuration is via the `JenticRuntime` builder:

```java
import dev.jentic.runtime.JenticRuntime;

// Load from a filesystem path (throws ConfigurationException on invalid config)
var runtime = JenticRuntime.builder()
    .fromFilesystemConfig("./jentic.yml")
    .build();

// Load from classpath resource (throws ConfigurationException on invalid config)
var runtime = JenticRuntime.builder()
    .fromClasspathConfig("jentic-test.yml")
    .build();

// Load default (see discovery order below)
var runtime = JenticRuntime.builder()
    .withDefaultConfig()
    .build();

// Provide a pre-built configuration object
JenticConfiguration config = JenticConfiguration.defaults();
var runtime = JenticRuntime.builder()
    .withConfiguration(config)
    .build();

// withConfiguration validates the provided config and throws ConfigurationException
// if it is null or fails validation (e.g. blank runtime.name).
```

If none of the config builder methods are called, `JenticRuntime` starts with built-in defaults.
**Note:** All builder config methods throw `ConfigurationException` (unchecked) if the loaded or provided configuration fails validation. No checked exception handling is required at the call site.

### Direct loader usage

If you need the `JenticConfiguration` object without building a runtime:

```java
import dev.jentic.runtime.config.DefaultConfigurationLoader;

var loader = new DefaultConfigurationLoader();
var config = loader.loadDefault();
// or
var config = loader.loadFromFile("./jentic.yml");
// or
var config = loader.loadFromClasspath("jentic-test.yml");
```

### Default discovery order (`loadDefault`)

1. `jentic.yml` in the current working directory (filesystem)
2. `jentic.yml` on the classpath

Built-in defaults are used if neither is found.

### Environment variable substitution

`${ENV_VAR}` syntax is supported inside YAML values.

---

## YAML Structure

The root element is `jentic:`. All sub-sections are optional and fall back to defaults if omitted.

```yaml
jentic:
  runtime:
    name: my-agent-system          # default: jentic-runtime
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
- Keys map to `dev.jentic.core.JenticConfiguration` via `dev.jentic.runtime.config.JenticConfigurationWrapper`.
- `basePackage` and `scanPaths` are merged into `scanPackages` at load time.
- Unknown keys are ignored by the loader.

---

## Provider Reference

### Messaging providers

| Value | Module required | Notes |
|---|---|---|
| `inmemory` | `jentic-runtime` (built-in) | Default; single JVM only |
| `redis` | `jentic-adapters` | Durable, multi-node; requires Lettuce on classpath |

#### Redis messaging (`provider: redis`)

Provider-specific keys go inside `messaging.properties` as string values:

```yaml
jentic:
  messaging:
    provider: redis
    properties:
      uri: redis://localhost:6379          # default: redis://localhost:6379
      consumer-group-prefix: jentic        # default: jentic
      read-block-timeout-ms: "2000"        # default: 2000
      max-stream-length: "100000"          # default: 100000
      pending-entries-timeout-ms: "30000"  # default: 30000
      max-delivery-attempts: "3"           # default: 3
```

> **Note:** Setting `provider: redis` in `jentic.yml` only records the provider name and properties
> in `JenticConfiguration`. The actual `RedisMessagingFactory` must be wired manually
> (see `jentic-adapters` documentation) or automatically via the **Spring Boot starter**,
> which reads these keys and creates the adapter bean.
> See [Spring Boot Starter](spring-boot-starter.md) for zero-wiring Redis setup.

---

### Directory providers

| Value | Module required | Notes |
|---|---|---|
| `local` | `jentic-runtime` (built-in) | Default; single JVM, survives restarts via in-memory state |
| `inmemory` | `jentic-runtime` (built-in) | Alias for `local` |
| `jdbc` | `jentic-adapters-persistence` | Durable, multi-node; requires a JDBC driver on classpath |

#### JDBC directory (`provider: jdbc`)

Provider-specific keys go inside `directory.properties` as string values:

```yaml
jentic:
  directory:
    provider: jdbc
    properties:
      url: jdbc:postgresql://localhost:5432/jentic   # required
      username: jentic                               # optional
      password: ${DB_PASSWORD}                       # optional
      pool-size: "10"                               # default: 10; must be a string
```

> **Note:** In a non-Spring-Boot context, `provider: jdbc` only records the intent in
> `JenticConfiguration`. `JdbcAgentDirectory` must be instantiated and passed to the runtime
> manually. The **Spring Boot starter** handles this automatically when
> `jentic-adapters-persistence` is on the classpath.
> See [JDBC Agent Directory](adapters/jdbc-directory.md) and [Spring Boot Starter](spring-boot-starter.md).

Supported JDBC URLs: `jdbc:postgresql://…`, `jdbc:mysql://…`, `jdbc:h2:…` (H2 for dev/test).

---

## Programmatic Configuration

You can configure the runtime entirely in code without a YAML file:

```java
import dev.jentic.runtime.JenticRuntime;

var runtime = JenticRuntime.builder()
    .scanPackage("dev.example.agents")   // add one package
    .scanPackages("dev.example.other")   // varargs variant
    .build();

runtime.start();
```

For full control over configuration values:

```java
import dev.jentic.core.JenticConfiguration;

var config = new JenticConfiguration(
    new JenticConfiguration.RuntimeConfig("my-system", "production", null),
    new JenticConfiguration.AgentsConfig(true, null, null, List.of("dev.example"), null),
    JenticConfiguration.MessagingConfig.defaults(),
    JenticConfiguration.DirectoryConfig.defaults(),
    JenticConfiguration.SchedulerConfig.defaults()
);

var runtime = JenticRuntime.builder()
    .withConfiguration(config)
    .build();
```

---

## Persistence

`jentic-runtime` provides a file-based persistence service suitable for development/testing. Persistence is enabled programmatically via `PersistenceManager`.

---

## Logging

Logging uses SLF4J. In tests/examples, Logback is included; provide your own `logback.xml` or `logback-test.xml` as needed.

---

## Example File

See `jentic-runtime/src/test/resources/jentic-test.yml` for a working example.