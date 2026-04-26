package dev.jentic.autoconfigure;

import dev.jentic.core.llm.LLMProvider;
import dev.jentic.core.telemetry.JenticTelemetry;
import dev.jentic.runtime.JenticRuntime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring Boot auto-configuration for Jentic.
 *
 * <p>Activated automatically when {@link JenticRuntime} is on the classpath.
 * All beans are guarded by {@code @ConditionalOnMissingBean} — user-declared beans always win.
 *
 * <p>LLM provider beans are grouped in the inner {@link LlmConfiguration} class and are
 * only activated when {@code jentic-adapters} is on the classpath and
 * {@code jentic.llm.provider} is set to a non-{@code none} value.
 *
 * <p>Lifecycle is managed via {@link SmartLifecycle} so that {@code start()} and {@code stop()}
 * block until the underlying {@link java.util.concurrent.CompletableFuture} completes. This
 * guarantees that all agents are fully started before the Spring context signals readiness,
 * and fully stopped before the context closes.
 */
@AutoConfiguration
@ConditionalOnClass(JenticRuntime.class)
@EnableConfigurationProperties(JenticProperties.class)
public class JenticAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(JenticAutoConfiguration.class);

    /**
     * Creates the {@link JenticRuntime} bean from {@link JenticProperties}.
     *
     * <p>If a user provides their own {@link LLMProvider} bean (either manually or via
     * {@link LlmConfiguration}), it is wired automatically via
     * {@link JenticRuntime.Builder#service}. If no provider bean is present (the default when
     * {@code jentic.llm.provider=none}), the runtime starts without LLM support.
     *
     * <p>Lifecycle is handled separately by {@link #jenticRuntimeLifecycle}.
     */
    @Bean
    @ConditionalOnMissingBean
    public JenticRuntime jenticRuntime(JenticProperties props,
                                       ObjectProvider<LLMProvider> llmProvider,
                                       ObjectProvider<JenticTelemetry> telemetry) {
        JenticRuntime.Builder builder = JenticRuntime.builder()
                .withConfiguration(props.toJenticConfiguration());

        llmProvider.ifAvailable(provider -> {
            log.debug("Wiring LLMProvider '{}' into JenticRuntime", provider.getProviderName());
            builder.service(LLMProvider.class, provider);
        });

        builder.telemetry(telemetry.getIfAvailable(JenticTelemetry::noop));

        log.debug("Building JenticRuntime: runtime.name={}", props.runtime().name());
        return builder.build();
    }

    /**
     * Manages the {@link JenticRuntime} lifecycle within the Spring context.
     *
     * <p>Uses {@link SmartLifecycle} instead of {@code initMethod}/{@code destroyMethod}
     * because {@link JenticRuntime#start()} and {@link JenticRuntime#stop()} return
     * {@link java.util.concurrent.CompletableFuture}. Calling {@code .join()} here ensures
     * the context does not proceed until all agents are fully started / stopped.
     *
     * <p>Phase {@code Integer.MAX_VALUE - 1} ensures the runtime starts after all other
     * infrastructure beans (data sources, messaging, etc.) and stops before them.
     */
    @Bean
    @ConditionalOnMissingBean(name = "jenticRuntimeLifecycle")
    public SmartLifecycle jenticRuntimeLifecycle(JenticRuntime jenticRuntime) {
        return new SmartLifecycle() {

            private volatile boolean running = false;

            @Override
            public void start() {
                log.info("Starting JenticRuntime...");
                jenticRuntime.start().join();
                running = true;
                log.info("JenticRuntime started");
            }

            @Override
            public void stop() {
                log.info("Stopping JenticRuntime...");
                jenticRuntime.stop().join();
                running = false;
                log.info("JenticRuntime stopped");
            }

            @Override
            public boolean isRunning() {
                return running;
            }

            @Override
            public boolean isPauseable() {
                return false;
            }

            @Override
            public int getPhase() {
                return Integer.MAX_VALUE - 1;
            }
        };
    }

    // -------------------------------------------------------------------------
    // Telemetry bean — active only when OTel + jentic-adapters are on the classpath
    // -------------------------------------------------------------------------

    /**
     * Produces a {@link JenticTelemetry} bean when OpenTelemetry is on the classpath
     * and {@code jentic.telemetry.enabled=true} (the default).
     *
     * <p>When the exporter is {@code "none"} (the out-of-the-box default) or when the
     * user supplies their own {@code JenticTelemetry} bean, this configuration is skipped.
     *
     * @since 0.19.0
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "dev.jentic.adapters.telemetry.OtelTelemetryFactory")
    @ConditionalOnProperty(prefix = "jentic.telemetry", name = "enabled",
            havingValue = "true", matchIfMissing = true)
    static class TelemetryConfiguration {

        @Bean
        @ConditionalOnMissingBean(JenticTelemetry.class)
        public JenticTelemetry jenticTelemetry(JenticProperties props) {
            JenticProperties.Telemetry t = props.telemetry();
            if ("none".equalsIgnoreCase(t.exporter())) {
                return JenticTelemetry.noop();
            }
            var builder = dev.jentic.adapters.telemetry.OtelTelemetryFactory.builder()
                    .serviceName(t.serviceName())
                    .exporter(t.exporter());
            if (t.endpoint() != null && !t.endpoint().isBlank()) {
                builder.endpoint(t.endpoint());
            }
            return builder.build();
        }
    }

    // -------------------------------------------------------------------------
    // LLM provider beans — active only when jentic-adapters is on the classpath
    // -------------------------------------------------------------------------

    /**
     * Inner configuration activated only when {@code jentic-adapters} is present.
     * Each bean is additionally guarded by {@code @ConditionalOnProperty} matching
     * the specific provider name and by {@code @ConditionalOnMissingBean} so that
     * a user-provided {@link LLMProvider} always wins.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "dev.jentic.adapters.llm.LLMProviderFactory")
    static class LlmConfiguration {

        @Bean
        @ConditionalOnMissingBean(LLMProvider.class)
        @ConditionalOnProperty(prefix = "jentic.llm", name = "provider", havingValue = "openai")
        public LLMProvider openAiLlmProvider(JenticProperties props) {
            JenticProperties.Llm llm = props.llm();
            requireApiKey(llm, "openai");
            log.info("Creating OpenAI LLMProvider (model={})", effectiveModel(llm, "gpt-4o-mini"));
            return dev.jentic.adapters.llm.LLMProviderFactory.openai()
                    .apiKey(llm.apiKey())
                    .modelName(effectiveModel(llm, "gpt-4o-mini"))
                    .build();
        }

        @Bean
        @ConditionalOnMissingBean(LLMProvider.class)
        @ConditionalOnProperty(prefix = "jentic.llm", name = "provider", havingValue = "anthropic")
        public LLMProvider anthropicLlmProvider(JenticProperties props) {
            JenticProperties.Llm llm = props.llm();
            requireApiKey(llm, "anthropic");
            log.info("Creating Anthropic LLMProvider (model={})",
                    effectiveModel(llm, "claude-3-haiku-20240307"));
            return dev.jentic.adapters.llm.LLMProviderFactory.anthropic()
                    .apiKey(llm.apiKey())
                    .modelName(effectiveModel(llm, "claude-3-haiku-20240307"))
                    .build();
        }

        @Bean
        @ConditionalOnMissingBean(LLMProvider.class)
        @ConditionalOnProperty(prefix = "jentic.llm", name = "provider", havingValue = "ollama")
        public LLMProvider ollamaLlmProvider(JenticProperties props) {
            JenticProperties.Llm llm = props.llm();
            String baseUrl = llm.baseUrl() != null ? llm.baseUrl() : "http://localhost:11434";
            log.info("Creating Ollama LLMProvider (baseUrl={}, model={})",
                    baseUrl, effectiveModel(llm, "llama3.2"));
            return dev.jentic.adapters.llm.LLMProviderFactory.ollama()
                    .baseUrl(baseUrl)
                    .modelName(effectiveModel(llm, "llama3.2"))
                    .build();
        }

        private static String effectiveModel(JenticProperties.Llm llm, String defaultModel) {
            return llm.model() != null && !llm.model().isBlank() ? llm.model() : defaultModel;
        }

        private static void requireApiKey(JenticProperties.Llm llm, String provider) {
            if (llm.apiKey() == null || llm.apiKey().isBlank()) {
                throw new IllegalStateException(
                        "jentic.llm.api-key must be set when jentic.llm.provider=" + provider);
            }
        }

        private static final Logger log =
                LoggerFactory.getLogger(LlmConfiguration.class);
    }

    /**
     * Fail-fast guard: if the user configured a non-{@code none} provider but
     * {@code jentic-adapters} is missing from the classpath, throw a clear error
     * at context startup rather than silently starting without LLM support.
     */
    // -------------------------------------------------------------------------
    // Actuator health indicator — active only when actuator is on the classpath
    // -------------------------------------------------------------------------

    /**
     * Registers {@link JenticHealthIndicator} when {@code spring-boot-starter-actuator}
     * is present and a {@link JenticRuntime} bean exists in the context.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "org.springframework.boot.health.contributor.HealthIndicator")
    static class ActuatorConfiguration {

        @Bean
        @ConditionalOnMissingBean
        org.springframework.boot.health.contributor.HealthIndicator jenticHealthIndicator(
                JenticRuntime jenticRuntime) {
            return new JenticHealthIndicator(jenticRuntime);
        }
    }

    /**
     * Fail-fast guard: active only when {@code jentic-adapters} is absent. The bean
     * method throws if {@code jentic.llm.provider} is set to anything other than {@code none},
     * producing a clear error at context startup rather than silently starting without LLM.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnMissingClass("dev.jentic.adapters.llm.LLMProviderFactory")
    static class LlmMissingAdaptersGuard {

        @Bean
        public String jenticLlmAdaptersMissingGuard(JenticProperties props) {
            String provider = props.llm().provider();
            if (!"none".equalsIgnoreCase(provider)) {
                throw new IllegalStateException(
                        "jentic.llm.provider=" + provider
                        + " requires 'jentic-adapters' on the classpath. "
                        + "Add <dependency><groupId>dev.jentic</groupId>"
                        + "<artifactId>jentic-adapters</artifactId></dependency> to your pom.xml.");
            }
            return "llm-adapters-guard-noop";
        }
    }
}