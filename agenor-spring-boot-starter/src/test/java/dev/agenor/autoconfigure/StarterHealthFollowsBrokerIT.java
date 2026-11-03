package dev.agenor.autoconfigure;

import dev.agenor.runtime.AgenorRuntime;
import dev.agenor.runtime.agent.BaseAgent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.EnabledIfDockerAvailable;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A node that cannot reach its broker delivers nothing while every agent stays {@code RUNNING},
 * so a health check that looks only at the agents answers {@code UP} for as long as the outage
 * lasts, and an orchestrator has no reason to act on it.
 *
 * <p>Enable with: {@code mvn verify -Dintegration.tests.enabled=true -pl agenor-spring-boot-starter}
 */
@Testcontainers
@EnabledIfSystemProperty(named = "integration.tests.enabled", matches = "true")
@EnabledIfDockerAvailable
@DisplayName("Starter — health follows the broker (Valkey)")
class StarterHealthFollowsBrokerIT {

    /** The probe waits at most 1 s, and a closed connection answers at once. */
    private static final Duration WITHIN = Duration.ofSeconds(10);

    @Test
    @DisplayName("health goes DOWN when the broker goes away, with the agents still running")
    void healthGoesDown_whenBrokerGoesAway() {
        try (GenericContainer<?> valkey = new GenericContainer<>("valkey/valkey:8")
                .withExposedPorts(6379)
                .withStartupTimeout(Duration.ofSeconds(60))) {
            valkey.start();

            new ApplicationContextRunner()
                    .withConfiguration(AutoConfigurations.of(AgenorAutoConfiguration.class))
                    .withUserConfiguration(OneAgent.class)
                    .withPropertyValues(
                            "agenor.messaging.provider=redis",
                            "agenor.messaging.redis.uri=redis://" + valkey.getHost() + ":"
                                    + valkey.getMappedPort(6379))
                    .run(ctx -> {
                        assertThat(ctx).hasNotFailed();
                        var indicator = ctx.getBean(AgenorHealthIndicator.class);

                        Health before = indicator.health();
                        assertThat(before.getStatus()).isEqualTo(Status.UP);
                        assertThat(before.getDetails())
                                .containsEntry("agents.running", 1)
                                .containsEntry("transport.type", "redis")
                                .containsEntry("transport.reachable", true);

                        valkey.stop();

                        Health after = awaitStatus(indicator, Status.DOWN);
                        assertThat(after.getDetails())
                                .as("the agents do not know, which is why they cannot be the signal")
                                .containsEntry("agents.running", 1)
                                .containsEntry("transport.type", "redis")
                                .containsEntry("transport.reachable", false);
                        assertThat(after.getDetails().toString())
                                .as("a broker URI can carry a password")
                                .doesNotContain("redis://");
                    });
        }
    }

    private static Health awaitStatus(AgenorHealthIndicator indicator, Status expected)
            throws InterruptedException {
        long deadline = System.nanoTime() + WITHIN.toNanos();
        Health last = indicator.health();
        while (!expected.equals(last.getStatus()) && System.nanoTime() < deadline) {
            Thread.sleep(100);
            last = indicator.health();
        }
        assertThat(last.getStatus()).isEqualTo(expected);
        return last;
    }

    @Configuration(proxyBeanMethods = false)
    static class OneAgent {
        @Bean
        BaseAgent agent(AgenorRuntime runtime) {
            var agent = new BaseAgent("health-it-agent", "Health IT agent") { };
            runtime.registerAgent(agent);
            return agent;
        }
    }
}
