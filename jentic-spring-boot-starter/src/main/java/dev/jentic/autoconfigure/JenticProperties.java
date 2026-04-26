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
 *     provider: inmemory
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
     * @param provider   messaging provider implementation; default {@code inmemory}
     * @param properties provider-specific configuration key/value pairs
     */
    public record Messaging(
            @DefaultValue("inmemory") String provider,
            @DefaultValue Map<String, String> properties
    ) {}

    /**
     * Directory
     * 
     * @param provider   agent directory implementation; default {@code local}
     * @param properties provider-specific configuration key/value pairs
     */
    public record Directory(
            @DefaultValue("local") String provider,
            @DefaultValue Map<String, String> properties
    ) {}

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
                        messaging().properties().isEmpty() ? null : messaging().properties()
                ),
                new JenticConfiguration.DirectoryConfig(
                        directory().provider(),
                        directory().properties().isEmpty() ? null : directory().properties()
                ),
                new JenticConfiguration.SchedulerConfig(
                        scheduler().provider(),
                        scheduler().threadPoolSize(),
                        null
                )
        );
    }
}