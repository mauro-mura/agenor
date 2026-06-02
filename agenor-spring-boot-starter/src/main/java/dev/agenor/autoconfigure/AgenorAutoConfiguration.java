package dev.agenor.autoconfigure;

import dev.agenor.core.AgentDirectory;
import dev.agenor.core.directory.AgentDiscovery;
import dev.agenor.core.directory.AgentPresence;
import dev.agenor.core.directory.AgentRegistry;
import dev.agenor.core.directory.AgentResolver;
import dev.agenor.core.hitl.ApprovalGate;
import dev.agenor.core.llm.LLMProvider;
import dev.agenor.core.messaging.MessageDispatcher;
import dev.agenor.core.telemetry.AgenorTelemetry;
import dev.agenor.runtime.AgenorRuntime;
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
 * <p>Activated automatically when {@link AgenorRuntime} is on the classpath.
 * All beans are guarded by {@code @ConditionalOnMissingBean} — user-declared beans always win.
 *
 * <p>LLM provider beans are grouped in the inner {@link LlmConfiguration} class and are
 * only activated when {@code agenor-adapters} is on the classpath and
 * {@code agenor.llm.provider} is set to a non-{@code none} value.
 *
 * <p>Lifecycle is managed via {@link SmartLifecycle} so that {@code start()} and {@code stop()}
 * block until the underlying {@link java.util.concurrent.CompletableFuture} completes. This
 * guarantees that all agents are fully started before the Spring context signals readiness,
 * and fully stopped before the context closes.
 */
@AutoConfiguration
@ConditionalOnClass(AgenorRuntime.class)
@EnableConfigurationProperties(AgenorProperties.class)
public class AgenorAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(AgenorAutoConfiguration.class);

    /**
     * Creates the {@link AgenorRuntime} bean from {@link AgenorProperties}.
     *
     * <p>If a user provides their own {@link LLMProvider} bean (either manually or via
     * {@link LlmConfiguration}), it is wired automatically via
     * {@link AgenorRuntime.Builder#service}. If no provider bean is present (the default when
     * {@code agenor.llm.provider=none}), the runtime starts without LLM support.
     *
     * <p>The runtime builds its own default messaging and directory components internally
     * ({@code InMemoryMessageDispatcher} and {@code InMemoryAgentDirectory}). These are
     * exposed as individual capability beans by the methods below so that application code
     * can inject the narrowest interface it needs. To replace a capability with a custom
     * implementation, declare your own {@link AgenorRuntime} bean and configure it via
     * {@link AgenorRuntime.Builder} directly — user-declared beans always win due to
     * {@code @ConditionalOnMissingBean}.
     *
     * <p>Lifecycle is handled separately by {@link #AgenorRuntimeLifecycle}.
     */
    @SuppressWarnings("deprecation")
    @Bean
    @ConditionalOnMissingBean
    public AgenorRuntime AgenorRuntime(AgenorProperties props,
                                       ObjectProvider<LLMProvider> llmProvider,
                                       ObjectProvider<AgenorTelemetry> telemetry,
                                       ObjectProvider<ApprovalGate> approvalGate) {
        AgenorRuntime.Builder builder = AgenorRuntime.builder()
                .withConfiguration(props.toAgenorConfiguration());

        llmProvider.ifAvailable(provider -> {
            log.debug("Wiring LLMProvider '{}' into AgenorRuntime", provider.getProviderName());
            builder.service(LLMProvider.class, provider);
        });
        builder.telemetry(telemetry.getIfAvailable(AgenorTelemetry::noop));
        approvalGate.ifAvailable(gate -> {
            log.debug("Wiring ApprovalGate '{}' into AgenorRuntime", gate.getClass().getSimpleName());
            builder.approvalGate(gate);
        });

        log.debug("Building AgenorRuntime (in-memory messaging): runtime.name={}", props.runtime().name());
        return builder.build();
    }

    // -------------------------------------------------------------------------
    // Capability beans derived from AgenorRuntime
    // -------------------------------------------------------------------------

    /**
     * Exposes the {@link MessageDispatcher} bean from the runtime.
     *
     * <p>Consumers that want to override messaging should declare their own
     * {@link MessageDispatcher} bean — the runtime will pick it up via the
     * {@link ObjectProvider} in {@link #AgenorRuntime}.
     *
     * @since 0.20.0
     */
    @Bean
    @ConditionalOnMissingBean
    public MessageDispatcher AgenorMessageDispatcher(AgenorRuntime agenorRuntime) {
        return agenorRuntime.getMessageDispatcher();
    }

    /**
     * Exposes the {@link AgentDirectory} bean from the runtime.
     *
     * @since 0.20.0
     */
    @Bean
    @ConditionalOnMissingBean(AgentDirectory.class)
    public AgentDirectory AgenorAgentDirectory(AgenorRuntime agenorRuntime) {
        return agenorRuntime.getAgentDirectory();
    }

    /**
     * Exposes the {@link AgentRegistry} capability bean.
     *
     * <p>Consumers that want a custom registry should declare their own
     * {@link AgentRegistry} bean and it will be wired into the runtime automatically.
     *
     * @since 0.20.0
     */
    @Bean
    @ConditionalOnMissingBean
    public AgentRegistry AgenorAgentRegistry(AgentDirectory agentDirectory) {
        return agentDirectory;
    }

    /**
     * Exposes the {@link AgentResolver} capability bean.
     *
     * @since 0.20.0
     */
    @Bean
    @ConditionalOnMissingBean
    public AgentResolver AgenorAgentResolver(AgentDirectory agentDirectory) {
        return agentDirectory;
    }

    /**
     * Exposes the {@link AgentDiscovery} capability bean.
     *
     * @since 0.20.0
     */
    @Bean
    @ConditionalOnMissingBean
    public AgentDiscovery AgenorAgentDiscovery(AgentDirectory agentDirectory) {
        return agentDirectory;
    }

    /**
     * Exposes the {@link AgentPresence} capability bean.
     *
     * @since 0.20.0
     */
    @Bean
    @ConditionalOnMissingBean
    public AgentPresence AgenorAgentPresence(AgentDirectory agentDirectory) {
        return agentDirectory;
    }

    /**
     * Manages the {@link AgenorRuntime} lifecycle within the Spring context.
     *
     * <p>Uses {@link SmartLifecycle} instead of {@code initMethod}/{@code destroyMethod}
     * because {@link AgenorRuntime#start()} and {@link AgenorRuntime#stop()} return
     * {@link java.util.concurrent.CompletableFuture}. Calling {@code .join()} here ensures
     * the context does not proceed until all agents are fully started / stopped.
     *
     * <p>Phase {@code Integer.MAX_VALUE - 1} ensures the runtime starts after all other
     * infrastructure beans (data sources, messaging, etc.) and stops before them.
     */
    @Bean
    @ConditionalOnMissingBean(name = "AgenorRuntimeLifecycle")
    public SmartLifecycle AgenorRuntimeLifecycle(AgenorRuntime agenorRuntime) {
        return new SmartLifecycle() {

            private volatile boolean running = false;

            @Override
            public void start() {
                log.info("Starting AgenorRuntime...");
                agenorRuntime.start().join();
                running = true;
                log.info("AgenorRuntime started");
            }

            @Override
            public void stop() {
                log.info("Stopping AgenorRuntime...");
                agenorRuntime.stop().join();
                running = false;
                log.info("AgenorRuntime stopped");
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
    // Telemetry bean — active only when OTel + agenor-adapters are on the classpath
    // -------------------------------------------------------------------------

    /**
     * Produces a {@link AgenorTelemetry} bean when OpenTelemetry is on the classpath
     * and {@code agenor.telemetry.enabled=true} (the default).
     *
     * <p>When the exporter is {@code "none"} (the out-of-the-box default) or when the
     * user supplies their own {@code AgenorTelemetry} bean, this configuration is skipped.
     *
     * @since 0.19.0
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "dev.agenor.adapters.telemetry.OtelTelemetryFactory")
    @ConditionalOnProperty(prefix = "agenor.telemetry", name = "enabled",
            havingValue = "true", matchIfMissing = true)
    static class TelemetryConfiguration {

        @Bean
        @ConditionalOnMissingBean(AgenorTelemetry.class)
        public AgenorTelemetry AgenorTelemetry(AgenorProperties props) {
            AgenorProperties.Telemetry t = props.telemetry();
            if ("none".equalsIgnoreCase(t.exporter())) {
                return AgenorTelemetry.noop();
            }
            var builder = dev.agenor.adapters.telemetry.OtelTelemetryFactory.builder()
                    .serviceName(t.serviceName())
                    .exporter(t.exporter());
            if (t.endpoint() != null && !t.endpoint().isBlank()) {
                builder.endpoint(t.endpoint());
            }
            return builder.build();
        }
    }

    // -------------------------------------------------------------------------
    // LLM provider beans — active only when agenor-adapters is on the classpath
    // -------------------------------------------------------------------------

    /**
     * Inner configuration activated only when {@code agenor-adapters} is present.
     * Each bean is additionally guarded by {@code @ConditionalOnProperty} matching
     * the specific provider name and by {@code @ConditionalOnMissingBean} so that
     * a user-provided {@link LLMProvider} always wins.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "dev.agenor.adapters.llm.LLMProviderFactory")
    static class LlmConfiguration {

        @Bean
        @ConditionalOnMissingBean(LLMProvider.class)
        @ConditionalOnProperty(prefix = "agenor.llm", name = "provider", havingValue = "openai")
        public LLMProvider openAiLlmProvider(AgenorProperties props) {
            AgenorProperties.Llm llm = props.llm();
            requireApiKey(llm, "openai");
            log.info("Creating OpenAI LLMProvider (model={})", effectiveModel(llm, "gpt-4o-mini"));
            return dev.agenor.adapters.llm.LLMProviderFactory.openai()
                    .apiKey(llm.apiKey())
                    .modelName(effectiveModel(llm, "gpt-4o-mini"))
                    .build();
        }

        @Bean
        @ConditionalOnMissingBean(LLMProvider.class)
        @ConditionalOnProperty(prefix = "agenor.llm", name = "provider", havingValue = "anthropic")
        public LLMProvider anthropicLlmProvider(AgenorProperties props) {
            AgenorProperties.Llm llm = props.llm();
            requireApiKey(llm, "anthropic");
            log.info("Creating Anthropic LLMProvider (model={})",
                    effectiveModel(llm, "claude-3-haiku-20240307"));
            return dev.agenor.adapters.llm.LLMProviderFactory.anthropic()
                    .apiKey(llm.apiKey())
                    .modelName(effectiveModel(llm, "claude-3-haiku-20240307"))
                    .build();
        }

        @Bean
        @ConditionalOnMissingBean(LLMProvider.class)
        @ConditionalOnProperty(prefix = "agenor.llm", name = "provider", havingValue = "ollama")
        public LLMProvider ollamaLlmProvider(AgenorProperties props) {
            AgenorProperties.Llm llm = props.llm();
            String baseUrl = llm.baseUrl() != null ? llm.baseUrl() : "http://localhost:11434";
            log.info("Creating Ollama LLMProvider (baseUrl={}, model={})",
                    baseUrl, effectiveModel(llm, "llama3.2"));
            return dev.agenor.adapters.llm.LLMProviderFactory.ollama()
                    .baseUrl(baseUrl)
                    .modelName(effectiveModel(llm, "llama3.2"))
                    .build();
        }

        private static String effectiveModel(AgenorProperties.Llm llm, String defaultModel) {
            return llm.model() != null && !llm.model().isBlank() ? llm.model() : defaultModel;
        }

        private static void requireApiKey(AgenorProperties.Llm llm, String provider) {
            if (llm.apiKey() == null || llm.apiKey().isBlank()) {
                throw new IllegalStateException(
                        "agenor.llm.api-key must be set when agenor.llm.provider=" + provider);
            }
        }

        private static final Logger log =
                LoggerFactory.getLogger(LlmConfiguration.class);
    }

    /**
     * Fail-fast guard: if the user configured a non-{@code none} provider but
     * {@code agenor-adapters} is missing from the classpath, throw a clear error
     * at context startup rather than silently starting without LLM support.
     */
    // -------------------------------------------------------------------------
    // Actuator health indicator — active only when actuator is on the classpath
    // -------------------------------------------------------------------------

    /**
     * Registers {@link AgenorHealthIndicator} when {@code spring-boot-starter-actuator}
     * is present and a {@link AgenorRuntime} bean exists in the context.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "org.springframework.boot.health.contributor.HealthIndicator")
    static class ActuatorConfiguration {

        @Bean
        @ConditionalOnMissingBean
        org.springframework.boot.health.contributor.HealthIndicator AgenorHealthIndicator(
                AgenorRuntime agenorRuntime) {
            return new AgenorHealthIndicator(agenorRuntime);
        }
    }

    // -------------------------------------------------------------------------
    // Redis messaging beans — active only when Lettuce is on the classpath
    // -------------------------------------------------------------------------

    /**
     * Creates a {@link dev.agenor.adapters.messaging.redis.RedisMessagingFactory} and
     * exposes a Redis-backed {@link MessageDispatcher} bean when:
     * <ul>
     *   <li>{@code io.lettuce.core.RedisClient} is on the classpath</li>
     *   <li>{@code agenor.messaging.provider=redis} is configured</li>
     * </ul>
     *
     * <p>The {@link MessageDispatcher} bean is picked up by the {@link #AgenorRuntime}
     * bean via {@code ObjectProvider}, so the runtime uses Redis Streams for all agent
     * messaging without any changes to its internals.
     *
     * <p>The factory is closed automatically on context shutdown via {@code destroyMethod}.
     *
     * @since 0.21.0
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "io.lettuce.core.RedisClient")
    @ConditionalOnProperty(prefix = "agenor.messaging", name = "provider", havingValue = "redis")
    static class RedisMessagingConfiguration {

        @Bean(destroyMethod = "close")
        @ConditionalOnMissingBean
        public dev.agenor.adapters.messaging.redis.RedisMessagingFactory redisMessagingFactory(
                AgenorProperties props,
                ObjectProvider<AgenorTelemetry> telemetry) {
            var redis = props.messaging().redis();
            return dev.agenor.adapters.messaging.redis.RedisMessagingFactory.builder()
                    .uri(redis != null ? redis.uri() : "redis://localhost:6379")
                    .consumerGroupPrefix(redis != null ? redis.consumerGroupPrefix() : "agenor")
                    .readBlockTimeoutMs(redis != null ? redis.readBlockTimeoutMs() : 2000L)
                    .maxStreamLength(redis != null ? redis.maxStreamLength() : 100_000)
                    .pendingEntriesTimeoutMs(redis != null ? redis.pendingEntriesTimeoutMs() : 30_000L)
                    .maxDeliveryAttempts(redis != null ? redis.maxDeliveryAttempts() : 3)
                    .telemetry(telemetry.getIfAvailable(AgenorTelemetry::noop))
                    .build();
        }

        /**
         * Exposes the Redis-backed {@link MessageDispatcher} bean.
         *
         * <p>The {@link AgentResolver} is supplied lazily so that this bean can be
         * created before {@link AgenorRuntime} (which provides the resolver) without
         * causing a circular dependency. The resolver is only fetched at the moment
         * {@code sendTo()} is called, by which time the runtime has started.
         */
        @Bean
        @ConditionalOnMissingBean(MessageDispatcher.class)
        public MessageDispatcher redisMessageDispatcher(
                dev.agenor.adapters.messaging.redis.RedisMessagingFactory factory,
                ObjectProvider<AgentResolver> agentResolver) {
            return factory.messageDispatcher(agentResolver::getIfAvailable);
        }

        /**
         * Creates the {@link AgenorRuntime} wired with the Redis {@link MessageDispatcher}.
         *
         * <p>No cycle: {@code redisMessageDispatcher} depends only on
         * {@code RedisMessagingFactory} and a lazy {@code ObjectProvider<AgentResolver>}
         * (resolved at {@code sendTo()} call time, never at construction time).
         */
        @SuppressWarnings("deprecation")
        @Bean("AgenorRuntime")
        @ConditionalOnMissingBean(AgenorRuntime.class)
        public AgenorRuntime AgenorRuntimeWithRedis(
                AgenorProperties props,
                MessageDispatcher redisMessageDispatcher,
                ObjectProvider<LLMProvider> llmProvider,
                ObjectProvider<AgenorTelemetry> telemetry,
                ObjectProvider<ApprovalGate> approvalGate) {
            var builder = AgenorRuntime.builder()
                    .withConfiguration(props.toAgenorConfiguration())
                    .messageDispatcher(redisMessageDispatcher);
            llmProvider.ifAvailable(p -> builder.service(LLMProvider.class, p));
            builder.telemetry(telemetry.getIfAvailable(AgenorTelemetry::noop));
            approvalGate.ifAvailable(builder::approvalGate);
            log.debug("Building AgenorRuntime (Redis messaging): runtime.name={}", props.runtime().name());
            return builder.build();
        }
    }

    // -------------------------------------------------------------------------
    // JDBC agent directory — active only when agenor-adapters-persistence is on the classpath
    // -------------------------------------------------------------------------

    /**
     * Creates a JDBC-backed {@link dev.agenor.core.directory.AgentDirectory} when:
     * <ul>
     *   <li>{@code dev.agenor.adapters.persistence.directory.JdbcAgentDirectory} is on the classpath</li>
     *   <li>{@code agenor.directory.provider=jdbc} is configured</li>
     * </ul>
     *
     * <p>The factory bean holds the HikariCP pool and is closed automatically on context
     * shutdown via {@code destroyMethod}. The three JDBC capability beans ({@code AgentRegistry},
     * {@code AgentDiscovery}, {@code AgentResolver}) are wired into the runtime using the
     * per-capability builder setters; presence falls back to the default in-memory implementation.
     *
     * @since 0.22.0
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "dev.agenor.adapters.persistence.directory.JdbcAgentDirectory")
    @ConditionalOnProperty(prefix = "agenor.directory", name = "provider", havingValue = "jdbc")
    static class JdbcDirectoryConfiguration {

        @Bean(destroyMethod = "close")
        @ConditionalOnMissingBean
        public dev.agenor.adapters.persistence.directory.JdbcAgentDirectory jdbcAgentDirectory(
                AgenorProperties props,
                ObjectProvider<AgenorTelemetry> telemetry) {
            var jdbc = props.directory().jdbc();
            if (jdbc == null || jdbc.url() == null || jdbc.url().isBlank()) {
                throw new IllegalStateException(
                        "agenor.directory.provider=jdbc requires agenor.directory.jdbc.url to be set");
            }
            var config = new dev.agenor.adapters.persistence.directory.JdbcDirectoryConfig(
                    jdbc.url(), jdbc.username(), jdbc.password(), jdbc.poolSize(),
                    "classpath:db/migration/agenor-directory");
            log.debug("Creating JdbcAgentDirectory (url={})", jdbc.url());
            return dev.agenor.adapters.persistence.directory.JdbcAgentDirectory.create(
                    config, telemetry.getIfAvailable(AgenorTelemetry::noop));
        }

        @Bean
        @ConditionalOnMissingBean
        public dev.agenor.core.directory.AgentRegistry jdbcAgentRegistry(
                dev.agenor.adapters.persistence.directory.JdbcAgentDirectory dir) {
            return dir.registry();
        }

        @Bean
        @ConditionalOnMissingBean
        public dev.agenor.core.directory.AgentDiscovery jdbcAgentDiscovery(
                dev.agenor.adapters.persistence.directory.JdbcAgentDirectory dir) {
            return dir.discovery();
        }

        @Bean
        @ConditionalOnMissingBean
        public dev.agenor.core.directory.AgentResolver jdbcAgentResolver(
                dev.agenor.adapters.persistence.directory.JdbcAgentDirectory dir) {
            return dir.resolver();
        }

        /**
         * Creates the {@link AgenorRuntime} wired with JDBC directory capabilities.
         *
         * <p>Registry, discovery, and resolver are backed by the JDBC store.
         * Presence uses the default in-memory implementation (see
         * {@link dev.agenor.core.directory.AgentPresence} Javadoc for rationale).
         */
        @Bean("AgenorRuntime")
        @ConditionalOnMissingBean(AgenorRuntime.class)
        public AgenorRuntime AgenorRuntimeWithJdbcDirectory(
                AgenorProperties props,
                dev.agenor.core.directory.AgentRegistry agentRegistry,
                dev.agenor.core.directory.AgentDiscovery agentDiscovery,
                dev.agenor.core.directory.AgentResolver agentResolver,
                ObjectProvider<LLMProvider> llmProvider,
                ObjectProvider<AgenorTelemetry> telemetry,
                ObjectProvider<ApprovalGate> approvalGate) {
            var builder = AgenorRuntime.builder()
                    .withConfiguration(props.toAgenorConfiguration())
                    .agentRegistry(agentRegistry)
                    .agentDiscovery(agentDiscovery)
                    .agentResolver(agentResolver);
            llmProvider.ifAvailable(p -> builder.service(LLMProvider.class, p));
            builder.telemetry(telemetry.getIfAvailable(AgenorTelemetry::noop));
            approvalGate.ifAvailable(builder::approvalGate);
            log.debug("Building AgenorRuntime (JDBC directory): runtime.name={}", props.runtime().name());
            return builder.build();
        }
    }

    // -------------------------------------------------------------------------
    // JDBC HITL approval queue — active only when agenor-adapters-persistence is on the classpath
    // -------------------------------------------------------------------------

    /**
     * Creates a JDBC-backed {@link ApprovalGate} when:
     * <ul>
     *   <li>{@code dev.agenor.adapters.persistence.hitl.JdbcApprovalGate} is on the classpath</li>
     *   <li>{@code agenor.hitl.provider=jdbc} is configured</li>
     * </ul>
     *
     * <p>The gate is closed automatically on context shutdown via {@code destroyMethod}.
     * It reuses the JDBC URL from {@code agenor.directory.jdbc} when
     * {@code agenor.hitl.jdbc.url} is not explicitly set.
     *
     * <p>The gate is exposed as an {@link ApprovalGate} bean and wired into the runtime
     * via the {@link #AgenorRuntime} factory method (which accepts an
     * {@link ObjectProvider}{@code <ApprovalGate>}). No dedicated runtime override bean
     * is needed here — the default runtime picks up the gate automatically.
     *
     * @since 0.23.0
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "dev.agenor.adapters.persistence.hitl.JdbcApprovalGate")
    @ConditionalOnProperty(prefix = "agenor.hitl", name = "provider", havingValue = "jdbc")
    static class JdbcHitlConfiguration {

        @Bean(destroyMethod = "close")
        @ConditionalOnMissingBean(ApprovalGate.class)
        public dev.agenor.adapters.persistence.hitl.JdbcApprovalGate jdbcApprovalGate(
                AgenorProperties props) {
            String url = resolveHitlUrl(props);
            if (url == null || url.isBlank()) {
                throw new IllegalStateException(
                        "agenor.hitl.provider=jdbc requires a JDBC URL. "
                        + "Set agenor.hitl.jdbc.url or agenor.directory.jdbc.url.");
            }
            String username = resolveHitlUsername(props);
            String password  = resolveHitlPassword(props);
            int poolSize     = resolveHitlPoolSize(props);

            var config = new dev.agenor.adapters.persistence.directory.JdbcDirectoryConfig(
                    url, username, password, poolSize,
                    "classpath:db/migration/agenor-hitl");
            log.debug("Creating JdbcApprovalGate (url={})", url);

            var dataSource = createHitlDataSource(config);
            new dev.agenor.adapters.persistence.hitl.HitlSchemaManager(
                    dataSource, "classpath:db/migration/agenor-hitl").migrate();

            var gate = new dev.agenor.adapters.persistence.hitl.JdbcApprovalGate(dataSource, url);
            gate.recoverExpired();
            return gate;
        }

        private static String resolveHitlUrl(AgenorProperties props) {
            var hitlJdbc = props.hitl().jdbc();
            if (hitlJdbc != null && hitlJdbc.url() != null && !hitlJdbc.url().isBlank()) {
                return hitlJdbc.url();
            }
            var dirJdbc = props.directory().jdbc();
            return dirJdbc != null ? dirJdbc.url() : null;
        }

        private static String resolveHitlUsername(AgenorProperties props) {
            var hitlJdbc = props.hitl().jdbc();
            if (hitlJdbc != null && hitlJdbc.username() != null && !hitlJdbc.username().isBlank()) {
                return hitlJdbc.username();
            }
            var dirJdbc = props.directory().jdbc();
            return dirJdbc != null ? dirJdbc.username() : "";
        }

        private static String resolveHitlPassword(AgenorProperties props) {
            var hitlJdbc = props.hitl().jdbc();
            if (hitlJdbc != null && hitlJdbc.password() != null && !hitlJdbc.password().isBlank()) {
                return hitlJdbc.password();
            }
            var dirJdbc = props.directory().jdbc();
            return dirJdbc != null ? dirJdbc.password() : "";
        }

        private static int resolveHitlPoolSize(AgenorProperties props) {
            var hitlJdbc = props.hitl().jdbc();
            return hitlJdbc != null ? hitlJdbc.poolSize() : 5;
        }

        private static javax.sql.DataSource createHitlDataSource(
                dev.agenor.adapters.persistence.directory.JdbcDirectoryConfig config) {
            var hikari = new com.zaxxer.hikari.HikariConfig();
            hikari.setJdbcUrl(config.jdbcUrl());
            hikari.setUsername(config.username());
            hikari.setPassword(config.password());
            hikari.setMaximumPoolSize(config.maximumPoolSize());
            hikari.setPoolName("agenor-hitl-pool");
            return new com.zaxxer.hikari.HikariDataSource(hikari);
        }

        private static final Logger log =
                LoggerFactory.getLogger(JdbcHitlConfiguration.class);
    }

    /**
     * Fail-fast guard: active only when {@code agenor-adapters} is absent. The bean
     * method throws if {@code agenor.llm.provider} is set to anything other than {@code none},
     * producing a clear error at context startup rather than silently starting without LLM.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnMissingClass("dev.agenor.adapters.llm.LLMProviderFactory")
    static class LlmMissingAdaptersGuard {

        @Bean
        public String AgenorLlmAdaptersMissingGuard(AgenorProperties props) {
            String provider = props.llm().provider();
            if (!"none".equalsIgnoreCase(provider)) {
                throw new IllegalStateException(
                        "agenor.llm.provider=" + provider
                        + " requires 'agenor-adapters' on the classpath. "
                        + "Add <dependency><groupId>dev.agenor</groupId>"
                        + "<artifactId>agenor-adapters</artifactId></dependency> to your pom.xml.");
            }
            return "llm-adapters-guard-noop";
        }
    }
}
