package dev.jentic.autoconfigure;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for the Redis messaging conditional beans in {@link JenticAutoConfiguration}.
 *
 * <p>The {@code RedisMessagingConfiguration} inner class activates only when both:
 * <ul>
 *   <li>{@code io.lettuce.core.RedisClient} is on the classpath, and</li>
 *   <li>{@code jentic.messaging.provider=redis} is set.</li>
 * </ul>
 *
 * <p>Lettuce is an {@code optional} transitive dependency and is NOT present in the
 * starter's test classpath, so these tests verify the condition-guard behaviour.
 * End-to-end Redis wiring is covered by the integration tests in {@code jentic-adapters}.
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
                    dev.jentic.adapters.messaging.redis.RedisMessagingFactory.class));
    }

    @Test
    void redisFactoryAbsentWhenProviderExplicitlyInmemory() {
        runner
            .withPropertyValues("jentic.messaging.provider=inmemory")
            .run(ctx ->
                assertThat(ctx).doesNotHaveBean(
                        dev.jentic.adapters.messaging.redis.RedisMessagingFactory.class));
    }

    // -------------------------------------------------------------------------
    // No Redis beans when provider=redis but Lettuce is absent
    // -------------------------------------------------------------------------

    @Test
    void redisFactoryAbsentWhenLettuceNotOnClasspath() {
        runner
            .withClassLoader(new FilteredClassLoader("io.lettuce.core.RedisClient"))
            .withPropertyValues("jentic.messaging.provider=redis")
            .run(ctx ->
                assertThat(ctx).doesNotHaveBean(
                        dev.jentic.adapters.messaging.redis.RedisMessagingFactory.class));
    }

    // -------------------------------------------------------------------------
    // Redis property defaults
    // -------------------------------------------------------------------------

    @Test
    void redisPropertiesHaveCorrectDefaults() {
        runner.run(ctx -> {
            JenticProperties props = ctx.getBean(JenticProperties.class);
            JenticProperties.Messaging.Redis redis = props.messaging().redis();
            assertThat(redis.uri()).isEqualTo("redis://localhost:6379");
            assertThat(redis.consumerGroupPrefix()).isEqualTo("jentic");
            assertThat(redis.readBlockTimeoutMs()).isEqualTo(2000L);
            assertThat(redis.maxStreamLength()).isEqualTo(100_000);
            assertThat(redis.pendingEntriesTimeoutMs()).isEqualTo(30_000L);
            assertThat(redis.maxDeliveryAttempts()).isEqualTo(3);
        });
    }

    @Test
    void redisPropertiesBindFromApplicationYaml() {
        runner
            .withPropertyValues(
                "jentic.messaging.provider=redis",
                "jentic.messaging.redis.uri=redis://my-redis:6380",
                "jentic.messaging.redis.consumer-group-prefix=acme",
                "jentic.messaging.redis.read-block-timeout-ms=5000",
                "jentic.messaging.redis.max-stream-length=50000",
                "jentic.messaging.redis.pending-entries-timeout-ms=60000",
                "jentic.messaging.redis.max-delivery-attempts=5"
            )
            .run(ctx -> {
                JenticProperties.Messaging.Redis redis = ctx.getBean(JenticProperties.class)
                        .messaging().redis();
                assertThat(redis.uri()).isEqualTo("redis://my-redis:6380");
                assertThat(redis.consumerGroupPrefix()).isEqualTo("acme");
                assertThat(redis.readBlockTimeoutMs()).isEqualTo(5_000L);
                assertThat(redis.maxStreamLength()).isEqualTo(50_000);
                assertThat(redis.pendingEntriesTimeoutMs()).isEqualTo(60_000L);
                assertThat(redis.maxDeliveryAttempts()).isEqualTo(5);
            });
    }
}
