package dev.agenor.autoconfigure;

import dev.agenor.adapters.persistence.directory.JdbcAgentPresence;
import dev.agenor.core.AgentDescriptor;
import dev.agenor.core.AgentStatus;
import dev.agenor.core.directory.AgentPresence;
import dev.agenor.core.directory.AgentDirectory;
import dev.agenor.runtime.AgenorRuntime;
import dev.agenor.runtime.agent.BaseAgent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Auto-configuration of the presence capability and the heartbeat driver (ADR-028).
 */
@DisplayName("Auto-configuration — agent presence")
class AgenorDirectoryPresenceAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AgenorAutoConfiguration.class));

    /** A fresh in-memory database per test, so migrations and rows never collide. */
    private static String jdbcUrl(String name) {
        return "agenor.directory.jdbc.url=jdbc:h2:mem:presence_ac_" + name
                + ";DB_CLOSE_DELAY=-1;MODE=PostgreSQL";
    }

    // -------------------------------------------------------------------------
    // Opting in
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("presence stays in-memory by default")
    void presenceIsInMemoryByDefault() {
        runner.run(ctx -> {
            assertThat(ctx).doesNotHaveBean(JdbcAgentPresence.class);
            assertThat(ctx.getBean(AgentPresence.class)).isNotInstanceOf(JdbcAgentPresence.class);
        });
    }

    @Test
    @DisplayName("a JDBC directory alone does not move presence into the database")
    void jdbcDirectoryAloneLeavesPresenceInMemory() {
        // Given a JDBC directory but no presence opt-in — heartbeats are writes, and choosing
        // a JDBC directory must not silently start issuing one per agent per interval
        runner.withPropertyValues("agenor.directory.provider=jdbc", jdbcUrl("dir_only"))
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(
                            dev.agenor.adapters.persistence.directory.JdbcAgentDirectory.class);
                    assertThat(ctx).doesNotHaveBean(JdbcAgentPresence.class);
                    assertThat(ctx.getBean(AgentPresence.class))
                            .isNotInstanceOf(JdbcAgentPresence.class);
                });
    }

    @Test
    @DisplayName("presence=jdbc creates the bean and the runtime actually uses it")
    void presenceJdbcIsCreatedAndWired() {
        runner.withPropertyValues(
                        "agenor.directory.provider=jdbc",
                        "agenor.directory.presence=jdbc",
                        jdbcUrl("wired"))
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(JdbcAgentPresence.class);
                    assertThat(ctx.getBean(AgentPresence.class)).isInstanceOf(JdbcAgentPresence.class);

                    // The bean existing is not the same as the runtime using it. Registration
                    // goes to the JDBC registry, so only a JDBC presence can see the result —
                    // an in-memory one would answer UNKNOWN for an agent it never stored.
                    var directory = ctx.getBean(AgenorRuntime.class).getAgentDirectory();
                    directory.register(AgentDescriptor.builder("wired-agent")
                            .agentName("Wired")
                            .agentType("PresenceTestAgent")
                            .status(AgentStatus.RUNNING)
                            .build()).join();

                    assertThat(directory.getStatus("wired-agent").join())
                            .isEqualTo(AgentStatus.RUNNING);
                });
    }

    // -------------------------------------------------------------------------
    // The staleness window
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("with nothing heartbeating, the window is unbounded")
    void windowIsUnboundedWhenNothingHeartbeats() {
        // Given JDBC presence and no heartbeat interval, a bounded window would report every
        // agent UNKNOWN once it elapsed, on a healthy cluster, with nothing to explain it
        runner.withPropertyValues(
                        "agenor.directory.provider=jdbc",
                        "agenor.directory.presence=jdbc",
                        jdbcUrl("unbounded"))
                .run(ctx -> assertThat(ctx.getBean(JdbcAgentPresence.class).stalenessWindow())
                        .isEqualTo(JdbcAgentPresence.UNBOUNDED_STALENESS_WINDOW));
    }

    @Test
    @DisplayName("the window follows the heartbeat interval at three times its length")
    void windowFollowsHeartbeatInterval() {
        runner.withPropertyValues(
                        "agenor.directory.provider=jdbc",
                        "agenor.directory.presence=jdbc",
                        "agenor.directory.heartbeat-interval=30s",
                        jdbcUrl("derived"))
                .run(ctx -> assertThat(ctx.getBean(JdbcAgentPresence.class).stalenessWindow())
                        .isEqualTo(Duration.ofSeconds(90)));
    }

    @Test
    @DisplayName("an explicit window wins over the derived one")
    void explicitWindowWins() {
        runner.withPropertyValues(
                        "agenor.directory.provider=jdbc",
                        "agenor.directory.presence=jdbc",
                        "agenor.directory.heartbeat-interval=30s",
                        "agenor.directory.staleness-window=5m",
                        jdbcUrl("explicit"))
                .run(ctx -> assertThat(ctx.getBean(JdbcAgentPresence.class).stalenessWindow())
                        .isEqualTo(Duration.ofMinutes(5)));
    }

    // -------------------------------------------------------------------------
    // The heartbeat driver
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("a configured interval makes the runtime heartbeat, whatever the backend is")
    void heartbeatIntervalDrivesTheDefaultRuntime() {
        // Given the plain in-memory runtime and a fast cadence — the driver is independent of
        // which backend stores presence
        runner.withPropertyValues("agenor.directory.heartbeat-interval=50ms").run(ctx -> {
            var runtime = ctx.getBean(AgenorRuntime.class);
            var agent = new HeartbeatTestAgent();
            runtime.registerAgent(agent);
            agent.start().join();

            var directory = runtime.getAgentDirectory();
            var before = lastSeen(directory, "heartbeat-agent");

            // When two intervals pass / Then the agent's lastSeen has moved forward
            TimeUnit.MILLISECONDS.sleep(400);
            assertThat(lastSeen(directory, "heartbeat-agent")).isAfter(before);
        });
    }

    @Test
    @DisplayName("without an interval nothing heartbeats")
    void withoutAnIntervalNothingHeartbeats() {
        runner.run(ctx -> {
            var runtime = ctx.getBean(AgenorRuntime.class);
            var agent = new HeartbeatTestAgent();
            runtime.registerAgent(agent);
            agent.start().join();

            var directory = runtime.getAgentDirectory();
            var before = lastSeen(directory, "heartbeat-agent");

            TimeUnit.MILLISECONDS.sleep(400);
            assertThat(lastSeen(directory, "heartbeat-agent")).isEqualTo(before);
        });
    }

    private static Instant lastSeen(AgentDirectory directory, String agentId) {
        return directory.findById(agentId).join().orElseThrow().lastSeen();
    }

    static class HeartbeatTestAgent extends BaseAgent {
        HeartbeatTestAgent() {
            super("heartbeat-agent", "Heartbeat Agent");
        }
    }
}
