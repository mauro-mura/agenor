package dev.jentic.autoconfigure;

import dev.jentic.core.JenticConfiguration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Spring Boot configuration properties for Jentic (prefix: {@code jentic}).
 *
 * <p>Maps {@code application.yml} keys to {@link JenticConfiguration}. All Spring Boot
 * relaxed-binding rules apply (kebab-case ↔ camelCase ↔ UPPER_CASE env variables).
 *
 * <p>The starter intentionally bypasses the {@code jentic.yml} file discovery performed
 * by {@code DefaultConfigurationLoader}. Configuration comes exclusively from
 * {@code application.yml} / environment variables via this record. If you need to load
 * from a {@code jentic.yml} file, call
 * {@code JenticRuntime.builder().fromClasspathConfig("jentic.yml")} manually and declare
 * your own {@code JenticRuntime} bean (which will suppress this autoconfiguration via
 * {@code @ConditionalOnMissingBean}).
 *
 * <pre>{@code
 * jentic:
 *   runtime:
 *     name: my-system
 *     environment: production
 *   agents:
 *     base-package: com.example.agents
 *     scan-packages:
 *       - com.example.tasks
 *   scheduler:
 *     thread-pool-size: 10
 *   messaging:
 *     provider: redis
 *     redis:
 *       uri: redis://localhost:6379
 *   directory:
 *     provider: local
 *   llm:
 *     provider: openai
 *     api-key: ${OPENAI_API_KEY}
 *     model: gpt-4o-mini
 * }</pre>
 * 
 * @param runtime   runtime identity configuration (name, environment)
 * @param agents    agent discovery and package scanning settings
 * @param scheduler behavior scheduler settings (provider, thread pool)
 * @param messaging messaging provider settings
 * @param directory agent directory provider settings
 * @param llm       LLM provider configuration (provider, api-key, model)
 */
@ConfigurationProperties(prefix = "jentic")
public record JenticProperties(
        @DefaultValue Runtime runtime,
        @DefaultValue Agents agents,
        @DefaultValue Scheduler scheduler,
        @DefaultValue Messaging messaging,
        @DefaultValue Directory directory,
        @DefaultValue Hitl hitl,
        @DefaultValue Llm llm,
        @DefaultValue Telemetry telemetry
) {

	/**
	 * Runtime
	 * 
     * @param name        runtime instance name; default {@code jentic-runtime}
     * @param environment environment label ({@code development}, {@code staging},
     *                    {@code production}, {@code test}); default {@code development}
     * @param properties  arbitrary key/value pairs forwarded to {@code RuntimeConfig}
     */
    public record Runtime(
            @DefaultValue("jentic-runtime") String name,
            @DefaultValue("development") String environment,
            @DefaultValue Map<String, String> properties
    ) {}

    /**
     * Agents
     * 
     * {@code base-package} and {@code scan-packages} are both merged into
     * {@link JenticConfiguration.AgentsConfig} by the core constructor.
     * {@code scan-paths} is kept as a legacy alias consistent with the native YAML format.
     * 
     * @param autoDiscovery  whether to scan for {@code @JenticAgent} classes at startup;
     *                       default {@code true}
     * @param basePackage    root package to scan for agents
     * @param scanPackages   additional packages to scan (merged with {@code basePackage})
     * @param scanPaths      legacy alias for {@code scan-packages}; kept for compatibility
     *                       with native {@code jentic.yml} format
     */
    public record Agents(
            @DefaultValue("true") boolean autoDiscovery,
            String basePackage,
            @DefaultValue List<String> scanPackages,
            @DefaultValue List<String> scanPaths
    ) {}

    /**
     * Scheduler
     * 
     * @param provider       scheduler implementation; default {@code simple}
     * @param threadPoolSize thread pool size for behavior execution; default {@code 10}
     */
    public record Scheduler(
            @DefaultValue("simple") String provider,
            @DefaultValue("10") int threadPoolSize
    ) {}

    /**
     * Messaging
     *
     * <p>Redis provider example:
     * <pre>{@code
     * jentic:
     *   messaging:
     *     provider: redis
     *     redis:
     *       uri: redis://localhost:6379
     *       consumer-group-prefix: jentic
     *       read-block-timeout-ms: 2000
     *       max-stream-length: 100000
     *       pending-entries-timeout-ms: 30000
     *       max-delivery-attempts: 3
     * }</pre>
     *
     * @param provider messaging provider implementation: {@code inmemory} (default) or {@code redis}
     * @param redis    Redis Streams configuration; only read when {@code provider=redis}
     */
    public record Messaging(
            @DefaultValue("inmemory") String provider,
            Redis redis
    ) {
        /**
         * Redis Streams configuration.
         *
         * @param uri                     Redis connection URI; default {@code redis://localhost:6379}
         * @param consumerGroupPrefix     prefix for stream keys and consumer groups; default {@code jentic}
         * @param readBlockTimeoutMs      XREADGROUP BLOCK timeout in milliseconds; default {@code 2000}
         * @param maxStreamLength         max entries per stream before approximate trimming; default {@code 100000}
         * @param pendingEntriesTimeoutMs idle time before a pending entry is eligible for redelivery in ms; default {@code 30000}
         * @param maxDeliveryAttempts     max delivery attempts before dead-letter; default {@code 3}
         */
        public record Redis(
                @DefaultValue("redis://localhost:6379") String uri,
                @DefaultValue("jentic") String consumerGroupPrefix,
                @DefaultValue("2000") long readBlockTimeoutMs,
                @DefaultValue("100000") int maxStreamLength,
                @DefaultValue("30000") long pendingEntriesTimeoutMs,
                @DefaultValue("3") int maxDeliveryAttempts
        ) {}
    }

    /**
     * Directory
     *
     * <p>JDBC provider example:
     * <pre>{@code
     * jentic:
     *   directory:
     *     provider: jdbc
     *     jdbc:
     *       url: jdbc:postgresql://localhost:5432/mydb
     *       username: jentic
     *       password: ${DB_PASSWORD}
     *       pool-size: 10
     * }</pre>
     *
     * @param provider agent directory implementation: {@code local} (default), {@code inmemory},
     *                 or {@code jdbc}
     * @param jdbc     JDBC-specific configuration; only read when {@code provider=jdbc}
     */
    public record Directory(
            @DefaultValue("local") String provider,
            Jdbc jdbc
    ) {
        /**
         * JDBC directory configuration.
         *
         * @param url      JDBC connection URL (required when {@code provider=jdbc})
         * @param username database username; default empty string
         * @param password database password; default empty string
         * @param poolSize HikariCP connection pool size; default {@code 10}
         */
        public record Jdbc(
                String url,
                @DefaultValue("") String username,
                @DefaultValue("") String password,
                @DefaultValue("10") int poolSize
        ) {}
    }

    /**
     * HITL (Human-In-The-Loop) approval queue.
     *
     * <p>JDBC provider example:
     * <pre>{@code
     * jentic:
     *   hitl:
     *     provider: jdbc
     *     jdbc:
     *       url: jdbc:postgresql://localhost:5432/mydb
     *       username: jentic
     *       password: ${DB_PASSWORD}
     *       pool-size: 5
     * }</pre>
     *
     * @param provider HITL queue implementation: {@code inmemory} (default) or {@code jdbc}
     * @param jdbc     JDBC-specific configuration; reuses {@code directory.jdbc} values when absent
     * @since 0.23.0
     */
    public record Hitl(
            @DefaultValue("inmemory") String provider,
            HitlJdbc jdbc
    ) {
        /**
         * JDBC HITL configuration.
         *
         * <p>When null, the JDBC HITL gate reuses the directory JDBC connection pool
         * (if both are configured).
         *
         * @param url      JDBC connection URL (required when {@code provider=jdbc} and
         *                 {@code directory.provider != jdbc})
         * @param username database username; default empty string
         * @param password database password; default empty string
         * @param poolSize HikariCP connection pool size; default {@code 5}
         */
        public record HitlJdbc(
                String url,
                @DefaultValue("") String username,
                @DefaultValue("") String password,
                @DefaultValue("5") int poolSize
        ) {}
    }

    /**
     * LLM
     *
     * @param provider LLM provider to activate ({@code none}, {@code openai},
     *                 {@code anthropic}, {@code ollama}); default {@code none}
     * @param apiKey   API key for cloud providers (required for {@code openai} and
     *                 {@code anthropic}); use {@code ${ENV_VAR}} for injection
     * @param model    model name override; falls back to provider default if null
     * @param baseUrl  base URL for self-hosted providers (Ollama);
     *                 default {@code http://localhost:11434}
     */
    public record Llm(
            @DefaultValue("none") String provider,
            String apiKey,
            String model,
            String baseUrl
    ) {}

    /**
     * Telemetry (OpenTelemetry tracing).
     *
     * <p>Only effective when {@code jentic-adapters} is on the classpath and OTel
     * dependencies are present (declared as {@code optional}).
     *
     * @param enabled     whether telemetry is enabled; default {@code true}
     * @param exporter    exporter type: {@code otlp-http} (default), {@code otlp-grpc},
     *                    or {@code none} to disable exporting while keeping noop
     * @param endpoint    OTLP collector endpoint; defaults to {@code http://localhost:4318}
     *                    for HTTP and {@code http://localhost:4317} for gRPC
     * @param serviceName {@code service.name} resource attribute; default {@code jentic}
     * @since 0.19.0
     */
    public record Telemetry(
            @DefaultValue("true")    boolean enabled,
            @DefaultValue("none")    String exporter,
            String endpoint,
            @DefaultValue("jentic")  String serviceName
    ) {}

    // ── Conversion ────────────────────────────────────────────────────────────

    /**
     * Converts to {@link JenticConfiguration} for use with
     * {@link dev.jentic.runtime.JenticRuntime.Builder#withConfiguration}.
     *
     * <p>{@code agents.base-package} and {@code agents.scan-packages} are passed
     * separately; the {@link JenticConfiguration.AgentsConfig} constructor merges
     * them in the correct order (scanPackages first, then basePackage).
     *
     * <p>{@code agents.scan-paths} is passed as the {@code scanPaths} parameter —
     * the legacy alias supported by the native YAML loader.
     *
     * <p>Validation (e.g. blank {@code runtime.name}, invalid package names) is
     * performed by {@link dev.jentic.runtime.JenticRuntime.Builder#withConfiguration}
     * when the runtime bean is created, not here.
     */
    public JenticConfiguration toJenticConfiguration() {
        List<String> packages = new ArrayList<>(agents().scanPackages());

        String[] paths = agents().scanPaths().isEmpty()
                ? null
                : agents().scanPaths().toArray(new String[0]);

        return new JenticConfiguration(
                new JenticConfiguration.RuntimeConfig(
                        runtime().name(),
                        runtime().environment(),
                        runtime().properties().isEmpty() ? null : runtime().properties()
                ),
                new JenticConfiguration.AgentsConfig(
                        agents().autoDiscovery(),
                        agents().basePackage(),
                        paths,
                        packages.isEmpty() ? null : packages,
                        null
                ),
                new JenticConfiguration.MessagingConfig(
                        messaging().provider(),
                        buildMessagingProperties()
                ),
                new JenticConfiguration.DirectoryConfig(
                        directory().provider(),
                        buildDirectoryProperties()
                ),
                new JenticConfiguration.SchedulerConfig(
                        scheduler().provider(),
                        scheduler().threadPoolSize(),
                        null
                )
        );
    }

    private Map<String, String> buildMessagingProperties() {
        var redis = messaging().redis();
        if (redis == null) return null;
        return Map.of(
                "uri", redis.uri(),
                "consumer-group-prefix", redis.consumerGroupPrefix(),
                "read-block-timeout-ms", String.valueOf(redis.readBlockTimeoutMs()),
                "max-stream-length", String.valueOf(redis.maxStreamLength()),
                "pending-entries-timeout-ms", String.valueOf(redis.pendingEntriesTimeoutMs()),
                "max-delivery-attempts", String.valueOf(redis.maxDeliveryAttempts())
        );
    }

    private Map<String, String> buildDirectoryProperties() {
        var jdbc = directory().jdbc();
        if (jdbc == null || jdbc.url() == null || jdbc.url().isBlank()) return null;
        return Map.of(
                "url", jdbc.url(),
                "username", jdbc.username(),
                "password", jdbc.password(),
                "pool-size", String.valueOf(jdbc.poolSize())
        );
    }
}