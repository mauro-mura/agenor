package dev.agenor.autoconfigure;

import dev.agenor.adapters.messaging.redis.RedisMessageDispatcher;
import dev.agenor.adapters.messaging.redis.RedisMessagingFactory;
import dev.agenor.core.messaging.MessageDispatcher;
import dev.agenor.runtime.JenticRuntime;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests for the Redis messaging conditional beans in {@link JenticAutoConfiguration}.
 *
 * <p>The {@code RedisMessagingConfiguration} inner class activates only when both:
 * <ul>
 *   <li>{@code io.lettuce.core.RedisClient} is on the classpath, and</li>
 *   <li>{@code agenor.messaging.provider=redis} is set.</li>
 * </ul>
 *
 * <p>Tests that verify Redis wiring with Lettuce present use a mock
 * {@link RedisMessagingFactory} supplied via {@code withBean} so that no actual
 * Redis connection is made. The {@code @ConditionalOnMissingBean} guard on the
 * auto-configuration's own factory skips its creation in favour of the mock.
 * End-to-end delivery semantics are covered by integration tests in
 * {@code agenor-adapters} (Testcontainers Valkey).
 */
class JenticRedisMessagingAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(JenticAutoConfiguration.class));

    // -------------------------------------------------------------------------
    // No Redis beans when provider is the default (inmemory)
    // -------------------------------------------------------------------------

    @Test
    void redisFactoryAbsentWhenProviderIsDefault() {
        runner.run(ctx ->
            assertThat(ctx).doesNotHaveBean(
                    RedisMessagingFactory.class));
    }

    @Test
    void redisFactoryAbsentWhenProviderExplicitlyInmemory() {
        runner
            .withPropertyValues("agenor.messaging.provider=inmemory")
            .run(ctx ->
                assertThat(ctx).doesNotHaveBean(
                        RedisMessagingFactory.class));
    }

    // -------------------------------------------------------------------------
    // No Redis beans when provider=redis but Lettuce is absent
    // -------------------------------------------------------------------------

    @Test
    void redisFactoryAbsentWhenLettuceNotOnClasspath() {
        runner
            .withClassLoader(new FilteredClassLoader("io.lettuce.core.RedisClient"))
            .withPropertyValues("agenor.messaging.provider=redis")
            .run(ctx ->
                assertThat(ctx).doesNotHaveBean(
                        RedisMessagingFactory.class));
    }

    // -------------------------------------------------------------------------
    // Redis property binding
    // -------------------------------------------------------------------------

    @Test
    void redisSubRecordNullByDefault() {
        runner.run(ctx -> {
            JenticProperties props = ctx.getBean(JenticProperties.class);
            assertThat(props.messaging().redis()).isNull();
        });
    }

    @Test
    void redisSubRecordBindsTypedFields() {
        runner
            .withPropertyValues(
                "agenor.messaging.redis.uri=redis://my-redis:6380",
                "agenor.messaging.redis.consumer-group-prefix=acme",
                "agenor.messaging.redis.read-block-timeout-ms=5000",
                "agenor.messaging.redis.max-stream-length=50000",
                "agenor.messaging.redis.pending-entries-timeout-ms=60000",
                "agenor.messaging.redis.max-delivery-attempts=5"
            )
            .run(ctx -> {
                var redis = ctx.getBean(JenticProperties.class).messaging().redis();
                assertThat(redis).isNotNull();
                assertThat(redis.uri()).isEqualTo("redis://my-redis:6380");
                assertThat(redis.consumerGroupPrefix()).isEqualTo("acme");
                assertThat(redis.readBlockTimeoutMs()).isEqualTo(5000L);
                assertThat(redis.maxStreamLength()).isEqualTo(50000);
                assertThat(redis.pendingEntriesTimeoutMs()).isEqualTo(60000L);
                assertThat(redis.maxDeliveryAttempts()).isEqualTo(5);
            });
    }

    // -------------------------------------------------------------------------
    // Redis beans wired correctly when Lettuce IS on the classpath
    //
    // A mock RedisMessagingFactory is pre-registered via withBean() to bypass
    // the actual Redis connection. The @ConditionalOnMissingBean on the
    // auto-configuration's own factory defers to our mock, so the remaining
    // auto-wiring (redisMessageDispatcher → jenticRuntime) runs as normal.
    // -------------------------------------------------------------------------

    @Test
    void redisMessageDispatcherRegisteredWhenLettucePresent() {
        RedisMessageDispatcher mockDispatcher = mock(RedisMessageDispatcher.class);
        RedisMessagingFactory  mockFactory    = mockFactory(mockDispatcher);

        runner
            .withPropertyValues("agenor.messaging.provider=redis")
            .withBean(RedisMessagingFactory.class, () -> mockFactory)
            .run(ctx -> {
                assertThat(ctx).hasSingleBean(MessageDispatcher.class);
                assertThat(ctx.getBean(MessageDispatcher.class))
                        .isInstanceOf(RedisMessageDispatcher.class);
            });
    }

    @Test
    void jenticRuntimeIsWiredWithRedisDispatcherWhenLettucePresent() {
        RedisMessageDispatcher mockDispatcher = mock(RedisMessageDispatcher.class);
        RedisMessagingFactory  mockFactory    = mockFactory(mockDispatcher);

        runner
            .withPropertyValues("agenor.messaging.provider=redis")
            .withBean(RedisMessagingFactory.class, () -> mockFactory)
            .run(ctx -> {
                JenticRuntime runtime = ctx.getBean(JenticRuntime.class);
                assertThat(runtime.getMessageDispatcher())
                        .as("JenticRuntime must use the Redis dispatcher, not the in-memory default")
                        .isSameAs(mockDispatcher);
            });
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static RedisMessagingFactory mockFactory(RedisMessageDispatcher dispatcher) {
        RedisMessagingFactory factory = mock(RedisMessagingFactory.class);
        when(factory.messageDispatcher(any())).thenReturn(dispatcher);
        return factory;
    }
}
