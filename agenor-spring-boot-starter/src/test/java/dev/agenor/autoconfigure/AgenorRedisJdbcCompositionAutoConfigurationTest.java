package dev.agenor.autoconfigure;

import dev.agenor.adapters.messaging.redis.RedisMessageDispatcher;
import dev.agenor.adapters.messaging.redis.RedisMessagingFactory;
import dev.agenor.adapters.persistence.directory.JdbcAgentDirectory;
import dev.agenor.core.AgentEndpoint;
import dev.agenor.runtime.AgenorRuntime;
import dev.agenor.runtime.agent.BaseAgent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Redis messaging and a JDBC directory configured together — the documented multi-node setup.
 *
 * <p>Each backend used to bring its own runtime factory, and only one of them could win. The
 * JDBC one built a runtime with the in-memory dispatcher, so agents were advertised with no
 * node and a {@code local} transport, and a message to an agent on another node was lost
 * without an error. These tests pin that the runtime gets both backends.
 */
@DisplayName("Auto-configuration — Redis messaging with a JDBC directory")
class AgenorRedisJdbcCompositionAutoConfigurationTest {

    private static final AgentEndpoint REDIS_ENDPOINT =
            new AgentEndpoint("node-x", "redis", Map.of());

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AgenorAutoConfiguration.class));

    private static String jdbcUrl(String name) {
        return "agenor.directory.jdbc.url=jdbc:h2:mem:redis_jdbc_ac_" + name
                + ";DB_CLOSE_DELAY=-1;MODE=PostgreSQL";
    }

    @Test
    @DisplayName("the runtime uses the Redis dispatcher when a JDBC directory is configured too")
    void runtimeGetsBothBackends() {
        var dispatcher = redisDispatcher();
        runner.withPropertyValues("agenor.messaging.provider=redis",
                        "agenor.directory.provider=jdbc", jdbcUrl("both"))
                .withBean(RedisMessagingFactory.class, () -> mockFactory(dispatcher))
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx.getBean(AgenorRuntime.class).getMessageDispatcher())
                            .as("the runtime must route through Redis, not the in-memory default")
                            .isSameAs(dispatcher);
                });
    }

    @Test
    @DisplayName("an agent is advertised in the database under the Redis node")
    void agentIsAdvertisedWithTheRedisEndpoint() {
        var dispatcher = redisDispatcher();
        runner.withPropertyValues("agenor.messaging.provider=redis",
                        "agenor.directory.provider=jdbc", jdbcUrl("endpoint"))
                .withBean(RedisMessagingFactory.class, () -> mockFactory(dispatcher))
                .run(ctx -> {
                    // Given the runtime the starter built for both backends
                    var runtime = ctx.getBean(AgenorRuntime.class);
                    var dir = ctx.getBean(JdbcAgentDirectory.class);

                    // When an agent is registered
                    runtime.registerAgent(new RoutedAgent());

                    // Then a peer resolving it from the database learns which node reaches it
                    assertThat(awaitEndpoint(dir, RoutedAgent.ID)).contains(REDIS_ENDPOINT);
                });
    }

    /** Registration writes to the directory asynchronously; wait for the row. */
    private static Optional<AgentEndpoint> awaitEndpoint(JdbcAgentDirectory dir, String agentId)
            throws InterruptedException {
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (System.nanoTime() < deadline) {
            if (dir.discovery().findById(agentId).join().isPresent()) {
                return dir.resolver().resolveEndpoint(agentId).join();
            }
            Thread.sleep(20);
        }
        return Optional.empty();
    }

    private static RedisMessageDispatcher redisDispatcher() {
        RedisMessageDispatcher dispatcher = mock(RedisMessageDispatcher.class);
        when(dispatcher.localEndpoint()).thenReturn(Optional.of(REDIS_ENDPOINT));
        return dispatcher;
    }

    private static RedisMessagingFactory mockFactory(RedisMessageDispatcher dispatcher) {
        RedisMessagingFactory factory = mock(RedisMessagingFactory.class);
        when(factory.messageDispatcher(any())).thenReturn(dispatcher);
        return factory;
    }

    static class RoutedAgent extends BaseAgent {
        static final String ID = "routed-agent";

        RoutedAgent() {
            super(ID, "Routed Agent");
        }
    }
}
