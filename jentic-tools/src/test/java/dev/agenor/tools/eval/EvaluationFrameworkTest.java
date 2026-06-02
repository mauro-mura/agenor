package dev.agenor.tools.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import dev.agenor.runtime.AgenorRuntime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import dev.agenor.core.AgentStatus;
import dev.agenor.core.Message;
import dev.agenor.runtime.agent.BaseAgent;
import dev.agenor.tools.health.HealthCheckService.HealthReport;
import dev.agenor.tools.health.HealthCheckService.HealthStatus;
import dev.agenor.tools.metrics.MetricsService.MetricsSnapshot;

/**
 * Tests for the evaluation framework components.
 */
class EvaluationFrameworkTest {

    // =========================================================================
    // AssertionResult Tests
    // =========================================================================

    @Nested
    @DisplayName("AssertionResult")
    class AssertionResultTests {

        @Test
        @DisplayName("Should create passed assertion")
        void shouldCreatePassedAssertion() {
            AssertionResult result = AssertionResult.pass("test-assertion");

            assertThat(result.passed()).isTrue();
            assertThat(result.failed()).isFalse();
            assertThat(result.name()).isEqualTo("test-assertion");
        }

        @Test
        @DisplayName("Should create failed assertion with message")
        void shouldCreateFailedAssertion() {
            AssertionResult result = AssertionResult.fail("test-assertion", "Something went wrong");

            assertThat(result.passed()).isFalse();
            assertThat(result.failed()).isTrue();
            assertThat(result.message()).isEqualTo("Something went wrong");
        }

        @Test
        @DisplayName("Should create failed assertion with expected/actual")
        void shouldCreateFailedAssertionWithValues() {
            AssertionResult result = AssertionResult.fail(
                "value-check", "Values don't match", 100, 50
            );

            assertThat(result.failed()).isTrue();
            assertThat(result.expected()).isEqualTo(100);
            assertThat(result.actual()).isEqualTo(50);
        }

        @Test
        @DisplayName("Should format passed assertion")
        void shouldFormatPassedAssertion() {
            AssertionResult result = AssertionResult.pass("test-assertion");

            String formatted = result.format();

            assertThat(formatted).contains("✓");
            assertThat(formatted).contains("test-assertion");
        }

        @Test
        @DisplayName("Should format failed assertion with details")
        void shouldFormatFailedAssertionWithDetails() {
            AssertionResult result = AssertionResult.fail(
                "count-check", "Count mismatch", 10, 5
            );

            String formatted = result.format();

            assertThat(formatted).contains("✗");
            assertThat(formatted).contains("count-check");
            assertThat(formatted).contains("expected: 10");
            assertThat(formatted).contains("actual: 5");
        }
    }

    // =========================================================================
    // EvaluationResult Tests
    // =========================================================================

    @Nested
    @DisplayName("EvaluationResult")
    class EvaluationResultTests {

        @Test
        @DisplayName("Should create passed result")
        void shouldCreatePassedResult() {
            List<AssertionResult> assertions = List.of(
                AssertionResult.pass("test-1"),
                AssertionResult.pass("test-2")
            );

            EvaluationResult result = EvaluationResult.passed(
                "test-scenario",
                Duration.ofMillis(100),
                null, null, assertions
            );

            assertThat(result.passed()).isTrue();
            assertThat(result.failed()).isFalse();
            assertThat(result.status()).isEqualTo(EvaluationResult.Status.PASSED);
            assertThat(result.passedCount()).isEqualTo(2);
            assertThat(result.failedCount()).isEqualTo(0);
            assertThat(result.successRate()).isEqualTo(100.0);
        }

        @Test
        @DisplayName("Should create failed result")
        void shouldCreateFailedResult() {
            List<AssertionResult> assertions = List.of(
                AssertionResult.pass("test-1"),
                AssertionResult.fail("test-2", "Failed")
            );

            EvaluationResult result = EvaluationResult.failed(
                "test-scenario",
                Duration.ofMillis(100),
                null, null, assertions
            );

            assertThat(result.passed()).isFalse();
            assertThat(result.failed()).isTrue();
            assertThat(result.passedCount()).isEqualTo(1);
            assertThat(result.failedCount()).isEqualTo(1);
            assertThat(result.successRate()).isEqualTo(50.0);
        }

        @Test
        @DisplayName("Should create error result")
        void shouldCreateErrorResult() {
            EvaluationResult result = EvaluationResult.error(
                "test-scenario",
                Duration.ofMillis(50),
                "Connection timeout"
            );

            assertThat(result.status()).isEqualTo(EvaluationResult.Status.ERROR);
            assertThat(result.errorMessage()).isEqualTo("Connection timeout");
        }

        @Test
        @DisplayName("Should create timeout result")
        void shouldCreateTimeoutResult() {
            EvaluationResult result = EvaluationResult.timeout(
                "test-scenario",
                Duration.ofSeconds(30)
            );

            assertThat(result.status()).isEqualTo(EvaluationResult.Status.TIMEOUT);
            assertThat(result.errorMessage()).contains("30000ms");
        }

        @Test
        @DisplayName("Should return failed assertions")
        void shouldReturnFailedAssertions() {
            List<AssertionResult> assertions = List.of(
                AssertionResult.pass("test-1"),
                AssertionResult.fail("test-2", "Failed"),
                AssertionResult.pass("test-3"),
                AssertionResult.fail("test-4", "Also failed")
            );

            EvaluationResult result = EvaluationResult.failed(
                "test-scenario", Duration.ofMillis(100),
                null, null, assertions
            );

            List<AssertionResult> failed = result.failedAssertions();

            assertThat(failed).hasSize(2);
            assertThat(failed).extracting(AssertionResult::name)
                .containsExactly("test-2", "test-4");
        }
    }

    // =========================================================================
    // ScenarioBuilder Tests
    // =========================================================================

    @Nested
    @DisplayName("ScenarioBuilder")
    class ScenarioBuilderTests {

        @Test
        @DisplayName("Should build scenario with defaults")
        void shouldBuildWithDefaults() {
            Scenario scenario = Scenario.builder("test-scenario").build();

            assertThat(scenario.getId()).isEqualTo("test-scenario");
            assertThat(scenario.getTimeout()).isEqualTo(Duration.ofSeconds(30));
        }

        @Test
        @DisplayName("Should build scenario with custom timeout")
        void shouldBuildWithCustomTimeout() {
            Scenario scenario = Scenario.builder("test-scenario")
                .timeout(Duration.ofMinutes(5))
                .build();

            assertThat(scenario.getTimeout()).isEqualTo(Duration.ofMinutes(5));
        }

        @Test
        @DisplayName("Should build scenario with description")
        void shouldBuildWithDescription() {
            Scenario scenario = Scenario.builder("test-scenario")
                .description("Tests something important")
                .build();

            assertThat(scenario.getDescription()).isEqualTo("Tests something important");
        }

        @Test
        @DisplayName("Should execute setup action")
        void shouldExecuteSetupAction() {
            List<String> log = new ArrayList<>();

            Scenario scenario = Scenario.builder("test-scenario")
                .setup(runtime -> log.add("setup"))
                .build();

            scenario.setup(null);

            assertThat(log).containsExactly("setup");
        }

        @Test
        @DisplayName("Should execute verify action")
        void shouldExecuteVerifyAction() {
            Scenario scenario = Scenario.builder("test-scenario")
                .verify(ctx -> List.of(AssertionResult.pass("verified")))
                .build();

            List<AssertionResult> results = scenario.verify(null);

            assertThat(results).hasSize(1);
            assertThat(results.get(0).name()).isEqualTo("verified");
        }
    }

    // =========================================================================
    // EvaluationContext Tests
    // =========================================================================

    @Nested
    @DisplayName("EvaluationContext Assertions")
    class EvaluationContextTests {

        private EvaluationContext context;
        private AgenorRuntime mockRuntime;

        @BeforeEach
        void setUp() {
            mockRuntime = AgenorRuntime.builder().build();

            MetricsSnapshot metrics = new MetricsSnapshot(
                Map.of(
                    "counters", Map.of("messages.sent", 10L, "messages.dropped", 0L),
                    "timers", Map.of("message.processing", Map.of("meanMs", 5.0))
                ),
                Instant.now()
            );

            HealthReport health = new HealthReport(
                HealthStatus.up(),
                Map.of(
                    "runtime", HealthStatus.up(),
                    "memory", HealthStatus.up(),
                    "agents", HealthStatus.up().withDetail("running", 2),
                    "threads", HealthStatus.up()
                ),
                Duration.ofMinutes(5)
            );

            List<Message> messages = List.of(
                Message.builder().topic("test.topic").content("content1").build(),
                Message.builder().topic("test.topic").content("content2").build(),
                Message.builder().topic("other.topic").content("content3").build()
            );

            context = new EvaluationContext(
                mockRuntime,
                metrics, health, messages,
                Instant.now().minusSeconds(1),
                Instant.now()
            );
        }

        @Test
        @DisplayName("Should assert agent running")
        void shouldAssertAgentRunning() {
            mockRuntime.registerAgent(new TestAgent("running-agent", true));

            AssertionResult result = context.assertAgentRunning("running-agent");

            assertThat(result.passed()).isTrue();
        }

        @Test
        @DisplayName("Should fail assert agent running when not running")
        void shouldFailAssertAgentRunningWhenNotRunning() {
            mockRuntime.registerAgent(new TestAgent("stopped-agent", false));

            AssertionResult result = context.assertAgentRunning("stopped-agent");

            assertThat(result.failed()).isTrue();
            assertThat(result.message()).contains("not running");
        }

        @Test
        @DisplayName("Should fail assert agent running when not found")
        void shouldFailAssertAgentRunningWhenNotFound() {
            AssertionResult result = context.assertAgentRunning("nonexistent");

            assertThat(result.failed()).isTrue();
            assertThat(result.message()).contains("not found");
        }

        @Test
        @DisplayName("Should assert message received")
        void shouldAssertMessageReceived() {
            AssertionResult result = context.assertMessageReceived("test.topic");

            assertThat(result.passed()).isTrue();
        }

        @Test
        @DisplayName("Should fail assert message received when not found")
        void shouldFailAssertMessageReceivedWhenNotFound() {
            AssertionResult result = context.assertMessageReceived("nonexistent.topic");

            assertThat(result.failed()).isTrue();
            assertThat(result.message()).contains("nonexistent.topic");
        }

        @Test
        @DisplayName("Should assert message count")
        void shouldAssertMessageCount() {
            AssertionResult result = context.assertMessageCount(3);

            assertThat(result.passed()).isTrue();
        }

        @Test
        @DisplayName("Should fail assert message count when mismatch")
        void shouldFailAssertMessageCountWhenMismatch() {
            AssertionResult result = context.assertMessageCount(10);

            assertThat(result.failed()).isTrue();
            assertThat(result.expected()).isEqualTo(10);
            assertThat(result.actual()).isEqualTo(3);
        }

        @Test
        @DisplayName("Should assert healthy")
        void shouldAssertHealthy() {
            AssertionResult result = context.assertHealthy();

            assertThat(result.passed()).isTrue();
        }

        @Test
        @DisplayName("Should assert component healthy")
        void shouldAssertComponentHealthy() {
            AssertionResult result = context.assertComponentHealthy("runtime");

            assertThat(result.passed()).isTrue();
        }

        @Test
        @DisplayName("Should assert no dropped messages")
        void shouldAssertNoDroppedMessages() {
            AssertionResult result = context.assertNoDroppedMessages();

            assertThat(result.passed()).isTrue();
        }

        @Test
        @DisplayName("Should assert custom condition")
        void shouldAssertCustomCondition() {
            AssertionResult passed = context.assertCondition("custom", true, "Should not appear");
            AssertionResult failed = context.assertCondition("custom2", false, "Condition failed");

            assertThat(passed.passed()).isTrue();
            assertThat(failed.failed()).isTrue();
            assertThat(failed.message()).isEqualTo("Condition failed");
        }
    }

    // =========================================================================
    // EvaluationReport Tests
    // =========================================================================

    @Nested
    @DisplayName("EvaluationReport")
    class EvaluationReportTests {

        private List<EvaluationResult> results;
        private EvaluationReport report;

        @BeforeEach
        void setUp() {
            results = List.of(
                EvaluationResult.passed("scenario-1", Duration.ofMillis(100), null, null,
                    List.of(AssertionResult.pass("a1"), AssertionResult.pass("a2"))),
                EvaluationResult.passed("scenario-2", Duration.ofMillis(200), null, null,
                    List.of(AssertionResult.pass("a3"))),
                EvaluationResult.failed("scenario-3", Duration.ofMillis(150), null, null,
                    List.of(AssertionResult.pass("a4"), AssertionResult.fail("a5", "Failed")))
            );
            report = new EvaluationReport(results);
        }

        @Test
        @DisplayName("Should calculate total scenarios")
        void shouldCalculateTotalScenarios() {
            assertThat(report.totalScenarios()).isEqualTo(3);
        }

        @Test
        @DisplayName("Should calculate passed count")
        void shouldCalculatePassedCount() {
            assertThat(report.passedCount()).isEqualTo(2);
        }

        @Test
        @DisplayName("Should calculate failed count")
        void shouldCalculateFailedCount() {
            assertThat(report.failedCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("Should calculate success rate")
        void shouldCalculateSuccessRate() {
            assertThat(report.successRate()).isCloseTo(66.67, within(0.1));
        }

        @Test
        @DisplayName("Should calculate total duration")
        void shouldCalculateTotalDuration() {
            assertThat(report.totalDuration()).isEqualTo(Duration.ofMillis(450));
        }

        @Test
        @DisplayName("Should calculate total assertions")
        void shouldCalculateTotalAssertions() {
            assertThat(report.totalAssertions()).isEqualTo(5);
        }

        @Test
        @DisplayName("Should generate Markdown report")
        void shouldGenerateMarkdownReport() {
            String markdown = report.toMarkdown();

            assertThat(markdown).contains("# Agent Evaluation Report");
            assertThat(markdown).contains("## Summary");
            assertThat(markdown).contains("## Results");
            assertThat(markdown).contains("scenario-1");
            assertThat(markdown).contains("scenario-3");
            assertThat(markdown).contains("## Failures");
        }

        @Test
        @DisplayName("Should generate JSON report")
        void shouldGenerateJsonReport() {
            String json = report.toJson();

            assertThat(json).contains("\"totalScenarios\": 3");
            assertThat(json).contains("\"passed\": 2");
            assertThat(json).contains("\"failed\": 1");
            assertThat(json).contains("\"scenarioId\": \"scenario-1\"");
        }
    }

    // =========================================================================
    // StandardScenarios Tests
    // =========================================================================

    @Nested
    @DisplayName("StandardScenarios")
    class StandardScenariosTests {

        @Test
        @DisplayName("Should create lifecycle scenario")
        void shouldCreateLifecycleScenario() {
            TestAgent agent = new TestAgent("test-agent", true);
            Scenario scenario = StandardScenarios.lifecycle(agent);

            assertThat(scenario.getId()).isEqualTo("lifecycle-test-agent");
            assertThat(scenario.getDescription()).contains("lifecycle");
        }

        @Test
        @DisplayName("Should create health check scenario")
        void shouldCreateHealthCheckScenario() {
            Scenario scenario = StandardScenarios.healthCheck();

            assertThat(scenario.getId()).isEqualTo("health-check");
        }

        @Test
        @DisplayName("Should create messaging scenario")
        void shouldCreateMessagingScenario() {
            Scenario scenario = StandardScenarios.messaging("test.topic", "content");

            assertThat(scenario.getId()).contains("test.topic");
        }

        @Test
        @DisplayName("Should create throughput scenario")
        void shouldCreateThroughputScenario() {
            Scenario scenario = StandardScenarios.throughput(100, Duration.ofSeconds(5));

            assertThat(scenario.getId()).contains("100");
            assertThat(scenario.getTimeout()).isGreaterThan(Duration.ofSeconds(5));
        }
    }

    // =========================================================================
    // Test Helpers
    // =========================================================================

    static class TestAgent extends BaseAgent {
        private final boolean running;

        TestAgent(String agentId, boolean running) {
            super(agentId, "Test Agent");
            this.running = running;
        }

        @Override
        public boolean isRunning() {
            return running;
        }

        @Override
        public AgentStatus getStatus() {
            return running ? AgentStatus.RUNNING : AgentStatus.STOPPED;
        }
    }
}
