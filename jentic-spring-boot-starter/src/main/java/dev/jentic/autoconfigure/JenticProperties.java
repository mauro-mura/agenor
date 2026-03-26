package dev.jentic.autoconfigure;

import dev.jentic.core.JenticConfiguration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.ArrayList;
import java.util.List;

/**
 * Spring Boot configuration properties for Jentic (prefix: {@code jentic}).
 *
 * <p>Maps {@code application.yml} keys to {@link JenticConfiguration}. All Spring Boot
 * relaxed-binding rules apply (kebab-case ↔ camelCase ↔ UPPER_CASE env variables).
 *
 * <pre>{@code
 * jentic:
 *   runtime:
 *     name: my-system
 *     environment: production
 *   agents:
 *     base-package: com.example.agents
 *   scheduler:
 *     thread-pool-size: 10
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
        @DefaultValue Llm llm
) {

    public record Runtime(
            @DefaultValue("jentic-runtime") String name,
            @DefaultValue("development") String environment
    ) {}

    public record Agents(
            @DefaultValue("true") boolean autoDiscovery,
            String basePackage,
            @DefaultValue List<String> scanPackages
    ) {}

    public record Scheduler(
            @DefaultValue("10") int threadPoolSize
    ) {}

    public record Llm(
            @DefaultValue("none") String provider,
            String apiKey,
            String model,
            String baseUrl
    ) {}

    /**
     * Convert to the Jentic core {@link JenticConfiguration}.
     *
     * <p>{@code agents.base-package} and {@code agents.scan-packages} are merged into
     * {@link JenticConfiguration.AgentsConfig} following the same precedence rule as
     * the core constructor: scanPackages first, then basePackage.
     */
    public JenticConfiguration toJenticConfiguration() {
        List<String> mergedPackages = new ArrayList<>(agents().scanPackages());

        return new JenticConfiguration(
                new JenticConfiguration.RuntimeConfig(
                        runtime().name(),
                        runtime().environment(),
                        null
                ),
                new JenticConfiguration.AgentsConfig(
                        agents().autoDiscovery(),
                        agents().basePackage(),
                        null,
                        mergedPackages.isEmpty() ? null : mergedPackages,
                        null
                ),
                null, // messaging — Phase 3
                null, // directory — Phase 3
                new JenticConfiguration.SchedulerConfig(
                        "simple",
                        scheduler().threadPoolSize(),
                        null
                )
        );
    }
}