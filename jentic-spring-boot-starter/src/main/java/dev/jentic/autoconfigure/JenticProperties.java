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
 */
@ConfigurationProperties(prefix = "jentic")
public record JenticProperties(
        @DefaultValue Runtime runtime,
        @DefaultValue Agents agents,
        @DefaultValue Scheduler scheduler,
        @DefaultValue Messaging messaging,
        @DefaultValue Directory directory,
        @DefaultValue Llm llm
) {

    // ── Runtime ───────────────────────────────────────────────────────────────

    public record Runtime(
            @DefaultValue("jentic-runtime") String name,
            @DefaultValue("development") String environment,
            @DefaultValue Map<String, String> properties
    ) {}

    // ── Agents ────────────────────────────────────────────────────────────────

    /**
     * {@code base-package} and {@code scan-packages} are both merged into
     * {@link JenticConfiguration.AgentsConfig} by the core constructor.
     * {@code scan-paths} is kept as a legacy alias consistent with the native YAML format.
     */
    public record Agents(
            @DefaultValue("true") boolean autoDiscovery,
            String basePackage,
            @DefaultValue List<String> scanPackages,
            @DefaultValue List<String> scanPaths
    ) {}

    // ── Scheduler ─────────────────────────────────────────────────────────────

    public record Scheduler(
            @DefaultValue("simple") String provider,
            @DefaultValue("10") int threadPoolSize
    ) {}

    // ── Messaging ─────────────────────────────────────────────────────────────

    public record Messaging(
            @DefaultValue("inmemory") String provider,
            @DefaultValue Map<String, String> properties
    ) {}

    // ── Directory ─────────────────────────────────────────────────────────────

    public record Directory(
            @DefaultValue("local") String provider,
            @DefaultValue Map<String, String> properties
    ) {}

    // ── LLM ───────────────────────────────────────────────────────────────────

    public record Llm(
            @DefaultValue("none") String provider,
            String apiKey,
            String model,
            String baseUrl
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