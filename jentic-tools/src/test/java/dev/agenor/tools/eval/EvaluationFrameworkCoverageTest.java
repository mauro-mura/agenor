package dev.agenor.tools.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import dev.agenor.core.AgentStatus;
import dev.agenor.core.Message;
import dev.agenor.runtime.JenticRuntime;
import dev.agenor.runtime.agent.BaseAgent;
import dev.agenor.tools.health.HealthCheckService.HealthReport;
import dev.agenor.tools.health.HealthCheckService.HealthStatus;
import dev.agenor.tools.metrics.MetricsService.MetricsSnapshot;

/**
 * Additional coverage tests for the eval package to reach 80%+ code coverage.
 */
class EvaluationFrameworkCoverageTest {

    // =========================================================================
    // AssertionResult extended coverage
    // =========================================================================

    @Nested
    @DisplayName("AssertionResult - extended")
    class AssertionResultExtendedTests {

        @Test
        @DisplayName("Should create passed assertion with message")
        void shouldCreatePassedWithMessage() {
            AssertionResult result = AssertionResult.pass("check", "All good");

            assertThat(result.passed()).isTrue();
            assertThat(result.message()).isEqualTo("All good");
            assertThat(result.name()).isEqualTo("check");
        }

        @Test
        @DisplayName("Should create withTiming result")
        void shouldCreateWithTiming() {
            Duration d = Duration.ofMillis(42);
            AssertionResult result = AssertionResult.withTiming("timed-check", true, "Done", d);

            assertThat(result.passed()).isTrue();
            assertThat(result.duration()).isEqualTo(d);
            String formatted = result.format();
            assertThat(formatted).contains("42ms");
        }

        @Test
        @DisplayName("Should include duration in format when present")
        void shouldIncludeDurationInFormat() {
            AssertionResult result = AssertionResult.withTiming("t", false, "Slow", Duration.ofMillis(100));

            assertThat(result.format()).contains("100ms");
        }

        @Test
        @DisplayName("toString should equal format")
        void toStringShouldEqualFormat() {
            AssertionResult result = AssertionResult.pass("x");

            assertThat(result.toString()).isEqualTo(result.format());
        }

        @Test
        @DisplayName("Should reject null name")
        void shouldRejectNullName() {
            assertThatThrownBy(() -> new AssertionResult(null, true, "msg", null, null, null))
                .isInstanceOf(NullPointerException.class);
        }
    }

    // =========================================================================
    // EvaluationResult extended coverage
    // =========================================================================

    @Nested
    @DisplayName("EvaluationResult - extended")
    class EvaluationResultExtendedTests {

        @Test
        @DisplayName("Should create skipped result")
        void shouldCreateSkippedResult() {
            EvaluationResult result = EvaluationResult.skipped("skip-test", "Not ready");

            assertThat(result.status()).isEqualTo(EvaluationResult.Status.SKIPPED);
            assertThat(result.errorMessage()).isEqualTo("Not ready");
            assertThat(result.passed()).isFalse();
            assertThat(result.failed()).isFalse();
        }

        @Test
        @DisplayName("Should format result correctly")
        void shouldFormatResult() {
            List<AssertionResult> assertions = List.of(
                AssertionResult.pass("a"),
                AssertionResult.fail("b", "boom")
            );
            EvaluationResult result = EvaluationResult.failed(
                "fmt-scenario", Duration.ofMillis(77), null, null, assertions);

            String formatted = result.format();
            assertThat(formatted).contains("fmt-scenario");
            assertThat(formatted).contains("FAILED");
            assertThat(formatted).contains("77ms");
            assertThat(formatted).contains("boom");
        }

        @Test
        @DisplayName("toString should equal format")
        void toStringShouldEqualFormat() {
            EvaluationResult result = EvaluationResult.error("e", Duration.ofMillis(10), "err");
            assertThat(result.toString()).isEqualTo(result.format());
        }

        @Test
        @DisplayName("successRate should be 0 for error with no assertions")
        void successRateShouldBeZeroForError() {
            EvaluationResult result = EvaluationResult.error("e", Duration.ofMillis(1), "err");
            assertThat(result.successRate()).isEqualTo(0.0);
        }

        @Test
        @DisplayName("successRate should be 100 for passed with no assertions")
        void successRateShouldBe100ForPassedWithNoAssertions() {
            EvaluationResult result = EvaluationResult.passed(
                "p", Duration.ofMillis(1), null, null, List.of());
            assertThat(result.successRate()).isEqualTo(100.0);
        }

        @Test
        @DisplayName("format should include error message when present")
        void shouldIncludeErrorMessageInFormat() {
            EvaluationResult result = EvaluationResult.error("e-test", Duration.ofMillis(5), "Timeout reached");
            assertThat(result.format()).contains("Timeout reached");
        }
    }

    // =========================================================================
    // ScenarioBuilder extended coverage
    // =========================================================================

    @Nested
    @DisplayName("ScenarioBuilder - extended")
    class ScenarioBuilderExtendedTests {

        @Test
        @DisplayName("Should execute execute action")
        void shouldExecuteExecuteAction() {
            List<String> log = new java.util.ArrayList<>();
            Scenario scenario = Scenario.builder("s")
                .execute(runtime -> log.add("execute"))
                .build();

            scenario.execute(null);
            assertThat(log).containsExactly("execute");
        }

        @Test
        @DisplayName("Should execute teardown action")
        void shouldExecuteTeardownAction() {
            List<String> log = new java.util.ArrayList<>();
            Scenario scenario = Scenario.builder("s")
                .teardown(runtime -> log.add("teardown"))
                .build();

            scenario.teardown(null);
            assertThat(log).containsExactly("teardown");
        }

        @Test
        @DisplayName("Should throw NPE when id is null")
        void shouldThrowNpeWhenIdNull() {
            assertThatThrownBy(() -> Scenario.builder(null).build())
                .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("Should throw NPE when timeout is null")
        void shouldThrowNpeWhenTimeoutNull() {
            assertThatThrownBy(() -> Scenario.builder("s").timeout(null))
                .isInstanceOf(NullPointerException.class);
        }
    }

    // =========================================================================
    // EvaluationContext extended coverage
    // =========================================================================

    @Nested
    @DisplayName("EvaluationContext - extended")
    class EvaluationContextExtendedTests {

        private EvaluationContext context;
        private JenticRuntime runtime;
        private MetricsSnapshot metrics;
        private HealthReport health;

        @BeforeEach
        void setUp() {
            runtime = JenticRuntime.builder().build();

            metrics = new MetricsSnapshot(
                Map.of(
                    "counters", Map.of("messages.sent", 10L, "messages.dropped", 0L),
                    "timers", Map.of("message.processing", Map.of("meanMs", 5.0))
                ),
                Instant.now()
            );

            health = new HealthReport(
                HealthStatus.up(),
                Map.of("runtime", HealthStatus.up()),
                Duration.ofMinutes(1)
            );

            context = new EvaluationContext(
                runtime, metrics, health,
                List.of(
                    Message.builder().topic("t1").content("c").build(),
                    Message.builder().topic("t2").content("c").build()
                ),
                Instant.now().minusSeconds(1),
                Instant.now()
            );
        }

        // --- Accessors ---

        @Test
        @DisplayName("Should expose runtime accessor")
        void shouldExposeRuntime() {
            assertThat(context.runtime()).isSameAs(runtime);
        }

        @Test
        @DisplayName("Should expose metrics accessor")
        void shouldExposeMetrics() {
            assertThat(context.metrics()).isSameAs(metrics);
        }

        @Test
        @DisplayName("Should expose healthReport accessor")
        void shouldExposeHealthReport() {
            assertThat(context.healthReport()).isSameAs(health);
        }

        @Test
        @DisplayName("Should expose messages accessor")
        void shouldExposeMessages() {
            assertThat(context.messages()).hasSize(2);
        }

        @Test
        @DisplayName("Should compute executionDuration")
        void shouldComputeExecutionDuration() {
            Duration d = context.executionDuration();
            assertThat(d).isGreaterThan(Duration.ZERO);
            assertThat(d).isLessThan(Duration.ofSeconds(5));
        }

        // --- Agent assertions ---

        @Test
        @DisplayName("Should assert agent exists when registered")
        void shouldAssertAgentExists() {
            runtime.registerAgent(new TestAgent("agent-x", false));
            AssertionResult result = context.assertAgentExists("agent-x");
            assertThat(result.passed()).isTrue();
        }

        @Test
        @DisplayName("Should fail assertAgentExists when not found")
        void shouldFailAssertAgentExistsWhenNotFound() {
            AssertionResult result = context.assertAgentExists("ghost");
            assertThat(result.failed()).isTrue();
            assertThat(result.message()).contains("not found");
        }

        @Test
        @DisplayName("Should assert agent count matches")
        void shouldAssertAgentCount() {
            runtime.registerAgent(new TestAgent("a1", false));
            runtime.registerAgent(new TestAgent("a2", false));
            AssertionResult result = context.assertAgentCount(2);
            assertThat(result.passed()).isTrue();
        }

        @Test
        @DisplayName("Should fail assertAgentCount when mismatch")
        void shouldFailAssertAgentCountWhenMismatch() {
            runtime.registerAgent(new TestAgent("a1", false));
            AssertionResult result = context.assertAgentCount(5);
            assertThat(result.failed()).isTrue();
            assertThat(result.expected()).isEqualTo(5);
        }

        // --- Message assertions ---

        @Test
        @DisplayName("Should assert message matching predicate")
        void shouldAssertMessageMatching() {
            AssertionResult result = context.assertMessageMatching("has-t1", m -> "t1".equals(m.topic()));
            assertThat(result.passed()).isTrue();
        }

        @Test
        @DisplayName("Should fail assertMessageMatching when no match")
        void shouldFailAssertMessageMatchingWhenNoMatch() {
            AssertionResult result = context.assertMessageMatching("no-match", m -> false);
            assertThat(result.failed()).isTrue();
        }

        @Test
        @DisplayName("Should assert message count at least when satisfied")
        void shouldAssertMessageCountAtLeast() {
            AssertionResult result = context.assertMessageCountAtLeast(1);
            assertThat(result.passed()).isTrue();
        }

        @Test
        @DisplayName("Should fail assertMessageCountAtLeast when too few")
        void shouldFailAssertMessageCountAtLeastWhenTooFew() {
            AssertionResult result = context.assertMessageCountAtLeast(10);
            assertThat(result.failed()).isTrue();
        }

        // --- Timing assertions ---

        @Test
        @DisplayName("Should pass assertCompletedWithin when fast enough")
        void shouldPassAssertCompletedWithin() {
            AssertionResult result = context.assertCompletedWithin(Duration.ofMinutes(1));
            assertThat(result.passed()).isTrue();
        }

        @Test
        @DisplayName("Should fail assertCompletedWithin when too slow")
        void shouldFailAssertCompletedWithin() {
            AssertionResult result = context.assertCompletedWithin(Duration.ofNanos(1));
            assertThat(result.failed()).isTrue();
        }

        @Test
        @DisplayName("Should pass assertResponseTimeUnder when mean is fast")
        void shouldPassAssertResponseTimeUnder() {
            // meanMs=5, threshold=100ms -> pass
            AssertionResult result = context.assertResponseTimeUnder(Duration.ofMillis(100));
            assertThat(result.passed()).isTrue();
        }

        @Test
        @DisplayName("Should fail assertResponseTimeUnder when mean too high")
        void shouldFailAssertResponseTimeUnder() {
            // meanMs=5, threshold=1ms -> fail
            AssertionResult result = context.assertResponseTimeUnder(Duration.ofNanos(1));
            assertThat(result.failed()).isTrue();
        }

        @Test
        @DisplayName("Should pass assertResponseTimeUnder when no timer data")
        void shouldPassAssertResponseTimeUnderWithNoTimers() {
            MetricsSnapshot noTimers = new MetricsSnapshot(Map.of(), Instant.now());
            EvaluationContext ctx = new EvaluationContext(
                runtime, noTimers, health, List.of(),
                Instant.now().minusSeconds(1), Instant.now()
            );
            AssertionResult result = ctx.assertResponseTimeUnder(Duration.ofMillis(10));
            assertThat(result.passed()).isTrue();
        }

        // --- Counter assertions ---

        @Test
        @DisplayName("Should pass assertCounter with correct value")
        void shouldPassAssertCounter() {
            AssertionResult result = context.assertCounter("messages.sent", 10L);
            assertThat(result.passed()).isTrue();
        }

        @Test
        @DisplayName("Should fail assertCounter when value mismatch")
        void shouldFailAssertCounterWhenMismatch() {
            AssertionResult result = context.assertCounter("messages.sent", 99L);
            assertThat(result.failed()).isTrue();
        }

        @Test
        @DisplayName("Should fail assertCounter when counter not found")
        void shouldFailAssertCounterWhenNotFound() {
            AssertionResult result = context.assertCounter("nonexistent", 1L);
            assertThat(result.failed()).isTrue();
            assertThat(result.message()).contains("Counter not found");
        }

        @Test
        @DisplayName("Should fail assertCounter when no counters in metrics")
        void shouldFailAssertCounterWhenNoCounters() {
            MetricsSnapshot noCounters = new MetricsSnapshot(Map.of(), Instant.now());
            EvaluationContext ctx = new EvaluationContext(
                runtime, noCounters, health, List.of(),
                Instant.now().minusSeconds(1), Instant.now()
            );
            AssertionResult result = ctx.assertCounter("messages.sent", 10L);
            assertThat(result.failed()).isTrue();
            assertThat(result.message()).contains("No counters");
        }

        @Test
        @DisplayName("Should pass assertCounterAtLeast when value is sufficient")
        void shouldPassAssertCounterAtLeast() {
            AssertionResult result = context.assertCounterAtLeast("messages.sent", 5L);
            assertThat(result.passed()).isTrue();
        }

        @Test
        @DisplayName("Should fail assertCounterAtLeast when value too low")
        void shouldFailAssertCounterAtLeastWhenTooLow() {
            AssertionResult result = context.assertCounterAtLeast("messages.sent", 100L);
            assertThat(result.failed()).isTrue();
        }

        @Test
        @DisplayName("Should fail assertCounterAtLeast when counter not found")
        void shouldFailAssertCounterAtLeastWhenNotFound() {
            AssertionResult result = context.assertCounterAtLeast("ghost.counter", 1L);
            assertThat(result.failed()).isTrue();
            assertThat(result.message()).contains("Counter not found");
        }

        @Test
        @DisplayName("Should fail assertCounterAtLeast when no counters")
        void shouldFailAssertCounterAtLeastWhenNoCounters() {
            MetricsSnapshot noCounters = new MetricsSnapshot(Map.of(), Instant.now());
            EvaluationContext ctx = new EvaluationContext(
                runtime, noCounters, health, List.of(),
                Instant.now().minusSeconds(1), Instant.now()
            );
            AssertionResult result = ctx.assertCounterAtLeast("messages.sent", 1L);
            assertThat(result.failed()).isTrue();
        }

        // --- Health assertions ---

        @Test
        @DisplayName("Should fail assertHealthy when unhealthy")
        void shouldFailAssertHealthyWhenUnhealthy() {
            HealthReport unhealthy = new HealthReport(
                HealthStatus.down("System down"),
                Map.of("runtime", HealthStatus.down("down")),
                Duration.ofMillis(0)
            );
            EvaluationContext ctx = new EvaluationContext(
                runtime, metrics, unhealthy, List.of(),
                Instant.now().minusSeconds(1), Instant.now()
            );
            AssertionResult result = ctx.assertHealthy();
            assertThat(result.failed()).isTrue();
        }

        @Test
        @DisplayName("Should fail assertComponentHealthy when component not found")
        void shouldFailAssertComponentHealthyWhenNotFound() {
            AssertionResult result = context.assertComponentHealthy("nonexistent");
            assertThat(result.failed()).isTrue();
            assertThat(result.message()).contains("not found");
        }

        @Test
        @DisplayName("Should fail assertComponentHealthy when component is down")
        void shouldFailAssertComponentHealthyWhenDown() {
            HealthReport withDown = new HealthReport(
                HealthStatus.up(),
                Map.of("db", HealthStatus.down("DB offline")),
                Duration.ofMillis(10)
            );
            EvaluationContext ctx = new EvaluationContext(
                runtime, metrics, withDown, List.of(),
                Instant.now().minusSeconds(1), Instant.now()
            );
            AssertionResult result = ctx.assertComponentHealthy("db");
            assertThat(result.failed()).isTrue();
        }

        // --- assertNoDroppedMessages negative case ---

        @Test
        @DisplayName("Should fail assertNoDroppedMessages when messages dropped")
        void shouldFailAssertNoDroppedMessagesWhenDropped() {
            MetricsSnapshot withDropped = new MetricsSnapshot(
                Map.of("counters", Map.of("messages.dropped", 5L)),
                Instant.now()
            );
            EvaluationContext ctx = new EvaluationContext(
                runtime, withDropped, health, List.of(),
                Instant.now().minusSeconds(1), Instant.now()
            );
            AssertionResult result = ctx.assertNoDroppedMessages();
            assertThat(result.failed()).isTrue();
        }
    }

    // =========================================================================
    // EvaluationReport extended coverage
    // =========================================================================

    @Nested
    @DisplayName("EvaluationReport - extended")
    class EvaluationReportExtendedTests {

        @Test
        @DisplayName("Should calculate average duration")
        void shouldCalculateAverageDuration() {
            List<EvaluationResult> results = List.of(
                EvaluationResult.passed("s1", Duration.ofMillis(100), null, null, List.of()),
                EvaluationResult.passed("s2", Duration.ofMillis(300), null, null, List.of())
            );
            EvaluationReport report = new EvaluationReport(results);

            assertThat(report.averageDuration()).isEqualTo(Duration.ofMillis(200));
        }

        @Test
        @DisplayName("Should return zero average duration when empty")
        void shouldReturnZeroAverageDurationWhenEmpty() {
            EvaluationReport report = new EvaluationReport(List.of());
            assertThat(report.averageDuration()).isEqualTo(Duration.ZERO);
        }

        @Test
        @DisplayName("Should calculate passed assertions count")
        void shouldCalculatePassedAssertions() {
            List<EvaluationResult> results = List.of(
                EvaluationResult.passed("s1", Duration.ofMillis(100), null, null,
                    List.of(AssertionResult.pass("a1"), AssertionResult.pass("a2"))),
                EvaluationResult.failed("s2", Duration.ofMillis(100), null, null,
                    List.of(AssertionResult.fail("a3", "fail")))
            );
            EvaluationReport report = new EvaluationReport(results);

            assertThat(report.passedAssertions()).isEqualTo(2);
        }

        @Test
        @DisplayName("Should calculate error count")
        void shouldCalculateErrorCount() {
            List<EvaluationResult> results = List.of(
                EvaluationResult.passed("s1", Duration.ofMillis(100), null, null, List.of()),
                EvaluationResult.error("s2", Duration.ofMillis(50), "Something broke"),
                EvaluationResult.error("s3", Duration.ofMillis(50), "Also broke")
            );
            EvaluationReport report = new EvaluationReport(results);

            assertThat(report.errorCount()).isEqualTo(2);
        }

        @Test
        @DisplayName("Should return 0 success rate when empty")
        void shouldReturnZeroSuccessRateWhenEmpty() {
            EvaluationReport report = new EvaluationReport(List.of());
            assertThat(report.successRate()).isEqualTo(0.0);
        }

        @Test
        @DisplayName("Should print summary to writer")
        void shouldPrintSummaryToWriter() {
            List<EvaluationResult> results = List.of(
                EvaluationResult.passed("scenario-ok", Duration.ofMillis(100), null, null,
                    List.of(AssertionResult.pass("a1"))),
                EvaluationResult.failed("scenario-fail", Duration.ofMillis(150), null, null,
                    List.of(AssertionResult.fail("a2", "Assertion failed")))
            );
            EvaluationReport report = new EvaluationReport(results);

            StringWriter sw = new StringWriter();
            report.printSummary(new PrintWriter(sw));
            String output = sw.toString();

            assertThat(output).contains("AGENT EVALUATION REPORT");
            assertThat(output).contains("scenario-ok");
            assertThat(output).contains("scenario-fail");
        }

        @Test
        @DisplayName("Should reject null results in constructor")
        void shouldRejectNullResults() {
            assertThatThrownBy(() -> new EvaluationReport(null))
                .isInstanceOf(NullPointerException.class);
        }
    }

    // =========================================================================
    // ScenarioRunner tests
    // =========================================================================

    @Nested
    @DisplayName("ScenarioRunner")
    class ScenarioRunnerTests {

        private JenticRuntime runtime;
        private ScenarioRunner runner;

        @BeforeEach
        void setUp() {
            runtime = JenticRuntime.builder().build();
            runtime.start();
            runner = new ScenarioRunner(runtime);
        }

        @AfterEach
        void tearDown() {
            runner.shutdown();
            runtime.stop();
        }

        @Test
        @DisplayName("Should run a passing scenario")
        void shouldRunPassingScenario() {
            Scenario scenario = Scenario.builder("simple-pass")
                .verify(ctx -> List.of(AssertionResult.pass("always-pass")))
                .build();

            EvaluationResult result = runner.run(scenario);

            assertThat(result.passed()).isTrue();
            assertThat(result.scenarioId()).isEqualTo("simple-pass");
        }

        @Test
        @DisplayName("Should run a failing scenario")
        void shouldRunFailingScenario() {
            Scenario scenario = Scenario.builder("simple-fail")
                .verify(ctx -> List.of(AssertionResult.fail("always-fail", "Intentional failure")))
                .build();

            EvaluationResult result = runner.run(scenario);

            assertThat(result.failed()).isTrue();
        }

        @Test
        @DisplayName("Should run scenario that throws an exception")
        void shouldHandleScenarioException() {
            Scenario scenario = Scenario.builder("error-scenario")
                .execute(rt -> { throw new RuntimeException("Execution error"); })
                .build();

            EvaluationResult result = runner.run(scenario);

            assertThat(result.status()).isEqualTo(EvaluationResult.Status.ERROR);
            assertThat(result.errorMessage()).contains("Execution error");
        }

        @Test
        @DisplayName("Should run all scenarios sequentially")
        void shouldRunAllScenarios() {
            List<Scenario> scenarios = List.of(
                Scenario.builder("s1").verify(ctx -> List.of(AssertionResult.pass("p"))).build(),
                Scenario.builder("s2").verify(ctx -> List.of(AssertionResult.pass("p"))).build()
            );

            List<EvaluationResult> results = runner.runAll(scenarios);

            assertThat(results).hasSize(2);
            assertThat(results).allMatch(EvaluationResult::passed);
        }

        @Test
        @DisplayName("Should run all scenarios in parallel")
        void shouldRunAllScenariosInParallel() {
            List<Scenario> scenarios = List.of(
                Scenario.builder("p1").verify(ctx -> List.of(AssertionResult.pass("ok"))).build(),
                Scenario.builder("p2").verify(ctx -> List.of(AssertionResult.pass("ok"))).build()
            );

            List<EvaluationResult> results = runner.runAllParallel(scenarios);

            assertThat(results).hasSize(2);
        }

        @Test
        @DisplayName("Should create report from results")
        void shouldCreateReport() {
            List<EvaluationResult> results = List.of(
                EvaluationResult.passed("s1", Duration.ofMillis(10), null, null, List.of())
            );

            EvaluationReport report = runner.createReport(results);

            assertThat(report.totalScenarios()).isEqualTo(1);
        }
    }

    // =========================================================================
    // StandardScenarios extended coverage
    // =========================================================================

    @Nested
    @DisplayName("StandardScenarios - extended")
    class StandardScenariosExtendedTests {

        @Test
        @DisplayName("Should create registration scenario")
        void shouldCreateRegistrationScenario() {
            TestAgent a1 = new TestAgent("agent-1", false);
            TestAgent a2 = new TestAgent("agent-2", false);
            Scenario scenario = StandardScenarios.registration(a1, a2);

            assertThat(scenario.getId()).isEqualTo("registration");
            assertThat(scenario.getDescription()).contains("registration");
        }

        @Test
        @DisplayName("Should create timing scenario")
        void shouldCreateTimingScenario() {
            Scenario scenario = StandardScenarios.timing(() -> {}, Duration.ofMillis(500));

            assertThat(scenario.getId()).isEqualTo("timing");
            assertThat(scenario.getTimeout()).isGreaterThan(Duration.ofMillis(500));
        }

        @Test
        @DisplayName("Should create requestResponse scenario")
        void shouldCreateRequestResponseScenario() {
            TestAgent req = new TestAgent("requester", true);
            TestAgent resp = new TestAgent("responder", true);
            Scenario scenario = StandardScenarios.requestResponse(req, resp, "req.topic", "resp.topic");

            assertThat(scenario.getId()).isEqualTo("request-response");
            assertThat(scenario.getDescription()).contains("request-response");
        }
    }

    // =========================================================================
    // Helper
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
