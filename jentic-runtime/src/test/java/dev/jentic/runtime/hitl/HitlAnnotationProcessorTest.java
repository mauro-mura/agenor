package dev.jentic.runtime.hitl;

import dev.jentic.core.Behavior;
import dev.jentic.core.BehaviorType;
import dev.jentic.core.hitl.ApprovalNotifier;
import dev.jentic.core.hitl.ApprovalRequest;
import dev.jentic.core.hitl.DefaultApprovalNotifier;
import dev.jentic.core.hitl.RequiresApproval;
import dev.jentic.runtime.agent.BaseAgent;
import dev.jentic.runtime.behavior.BaseBehavior;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.*;

@DisplayName("HitlAnnotationProcessor")
class HitlAnnotationProcessorTest {

    private ScheduledExecutorService scheduler;
    private InMemoryApprovalGate gate;

    @BeforeEach
    void setUp() {
        scheduler = Executors.newSingleThreadScheduledExecutor();
        gate = new InMemoryApprovalGate(scheduler);
    }

    @AfterEach
    void tearDown() {
        scheduler.shutdownNow();
    }

    // -------------------------------------------------------------------------
    // Test doubles
    // -------------------------------------------------------------------------

    static class TestAgent extends BaseAgent {
        TestAgent() { super("test-agent", "Test Agent"); }
    }

    /** Unannotated behavior — must NOT be wrapped. */
    static class PlainBehavior extends BaseBehavior {
        boolean executed;
        PlainBehavior() { super("plain", BehaviorType.ONE_SHOT, null); }
        @Override protected void action() { executed = true; }
    }

    /** Annotated with default sentinel notifier and default timeout. */
    @RequiresApproval
    static class DefaultAnnotatedBehavior extends BaseBehavior {
        boolean executed;
        DefaultAnnotatedBehavior() { super("annotated-default", BehaviorType.ONE_SHOT, null); }
        @Override protected void action() { executed = true; }
    }

    /** Annotated with custom timeout and explicit LoggingApprovalNotifier. */
    @RequiresApproval(timeout = "5m", notifier = LoggingApprovalNotifier.class)
    static class CustomAnnotatedBehavior extends BaseBehavior {
        CustomAnnotatedBehavior() { super("annotated-custom", BehaviorType.ONE_SHOT, null); }
        @Override protected void action() {}
    }

    /** Notifier with NO no-arg constructor — must trigger HitlWiringException. */
    public static class BrokenNotifier implements ApprovalNotifier {
        public BrokenNotifier(String required) {}
        @Override public void notify(ApprovalRequest request) {}
    }

    @RequiresApproval(notifier = BrokenNotifier.class)
    static class BrokenBehavior extends BaseBehavior {
        BrokenBehavior() { super("broken", BehaviorType.ONE_SHOT, null); }
        @Override protected void action() {}
    }

    /** Annotated behavior that signals execution via injected AtomicBoolean. */
    @RequiresApproval(timeout = "5m")
    static class TrackingBehavior extends BaseBehavior {
        private final AtomicBoolean executed;
        TrackingBehavior(AtomicBoolean executed) {
            super("tracking", BehaviorType.ONE_SHOT, null);
            this.executed = executed;
        }
        @Override protected void action() { executed.set(true); }
    }

    // -------------------------------------------------------------------------
    // process()
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("process()")
    class ProcessTests {

        @Test
        @DisplayName("unannotated behavior is not wrapped")
        void unannotated_notWrapped() {
            var agent = new TestAgent();
            var plain = new PlainBehavior();
            agent.addBehavior(plain);

            HitlAnnotationProcessor.process(agent, gate);

            assertThat(agent.getBehaviors())
                    .noneMatch(b -> b instanceof HumanCheckpointBehavior);
        }

        @Test
        @DisplayName("annotated behavior is replaced with HumanCheckpointBehavior")
        void annotated_wrappedWithCheckpoint() {
            var agent = new TestAgent();
            agent.addBehavior(new DefaultAnnotatedBehavior());

            HitlAnnotationProcessor.process(agent, gate);

            assertThat(agent.getBehaviors())
                    .hasSize(1)
                    .allMatch(b -> b instanceof HumanCheckpointBehavior);
        }

        @Test
        @DisplayName("original behavior ID is removed after wrapping")
        void annotated_originalIdRemoved() {
            var agent = new TestAgent();
            var b = new DefaultAnnotatedBehavior();
            agent.addBehavior(b);

            HitlAnnotationProcessor.process(agent, gate);

            assertThat(agent.getBehaviors())
                    .noneMatch(beh -> beh.getBehaviorId().equals("annotated-default"));
        }

        @Test
        @DisplayName("DefaultApprovalNotifier sentinel is replaced by LoggingApprovalNotifier")
        void sentinel_replacedByLogging() {
            // Verify sentinel detection — DefaultAnnotatedBehavior uses sentinel
            var notifier = HitlAnnotationProcessor.instantiateNotifier(
                    DefaultApprovalNotifier.class, DefaultAnnotatedBehavior.class);
            assertThat(notifier).isInstanceOf(LoggingApprovalNotifier.class);
        }

        @Test
        @DisplayName("notifier with no no-arg constructor throws HitlWiringException")
        void brokenNotifier_throwsAtBootstrap() {
            var agent = new TestAgent();
            agent.addBehavior(new BrokenBehavior());

            assertThatThrownBy(() -> HitlAnnotationProcessor.process(agent, gate))
                    .isInstanceOf(HitlAnnotationProcessor.HitlWiringException.class)
                    .hasMessageContaining("BrokenNotifier")
                    .hasMessageContaining("no-arg constructor");
        }

        @Test
        @DisplayName("unannotated behavior is preserved alongside wrapped ones")
        void mixed_plainPreservedAndAnnotatedWrapped() {
            var agent = new TestAgent();
            agent.addBehavior(new PlainBehavior());
            agent.addBehavior(new DefaultAnnotatedBehavior());

            HitlAnnotationProcessor.process(agent, gate);

            assertThat(agent.getBehaviors()).hasSize(2);
            assertThat(agent.getBehaviors())
                    .anyMatch(b -> b instanceof HumanCheckpointBehavior)
                    .anyMatch(b -> b instanceof PlainBehavior);
        }
    }

    // -------------------------------------------------------------------------
    // parseTimeout()
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("parseTimeout()")
    class ParseTimeoutTests {

        @Test
        @DisplayName("'5s' → Duration.ofSeconds(5)")
        void parseSeconds() {
            assertThat(HitlAnnotationProcessor.parseTimeout("5s", Object.class))
                    .isEqualTo(Duration.ofSeconds(5));
        }

        @Test
        @DisplayName("'10m' → Duration.ofMinutes(10)")
        void parseMinutes() {
            assertThat(HitlAnnotationProcessor.parseTimeout("10m", Object.class))
                    .isEqualTo(Duration.ofMinutes(10));
        }

        @Test
        @DisplayName("'2h' → Duration.ofHours(2)")
        void parseHours() {
            assertThat(HitlAnnotationProcessor.parseTimeout("2h", Object.class))
                    .isEqualTo(Duration.ofHours(2));
        }

        @Test
        @DisplayName("'30m' (default) → Duration.ofMinutes(30)")
        void parseDefault() {
            assertThat(HitlAnnotationProcessor.parseTimeout("30m", Object.class))
                    .isEqualTo(Duration.ofMinutes(30));
        }

        @Test
        @DisplayName("unrecognised format → HitlWiringException")
        void unknownFormat_throws() {
            assertThatThrownBy(() -> HitlAnnotationProcessor.parseTimeout("1d", Object.class))
                    .isInstanceOf(HitlAnnotationProcessor.HitlWiringException.class)
                    .hasMessageContaining("Unrecognised timeout format");
        }

        @Test
        @DisplayName("non-numeric value → HitlWiringException")
        void nonNumeric_throws() {
            assertThatThrownBy(() -> HitlAnnotationProcessor.parseTimeout("xm", Object.class))
                    .isInstanceOf(HitlAnnotationProcessor.HitlWiringException.class);
        }

        @Test
        @DisplayName("blank string → HitlWiringException")
        void blank_throws() {
            assertThatThrownBy(() -> HitlAnnotationProcessor.parseTimeout("  ", Object.class))
                    .isInstanceOf(HitlAnnotationProcessor.HitlWiringException.class);
        }
    }

    // -------------------------------------------------------------------------
    // Integration: approve via ApprovalService unblocks behavior
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("runtime integration: getApprovalService().approve(id) unblocks the agent behavior")
    void integration_approveUnblocksBehavior() throws Exception {
        var executed = new AtomicBoolean(false);
        var agent = new TestAgent();
        agent.addBehavior(new TrackingBehavior(executed));

        var service = new ApprovalService(gate);
        HitlAnnotationProcessor.process(agent, gate);

        // Checkpoint behavior replaces the original
        BaseBehavior checkpoint = (BaseBehavior) agent.getBehaviors().stream()
                .filter(b -> b instanceof HumanCheckpointBehavior)
                .findFirst().orElseThrow();
        checkpoint.setAgent(agent);

        // Execute checkpoint on a virtual thread (it will park waiting for approval)
        var future = checkpoint.execute();

        // Wait for the request to appear in the pending list
        long deadline = System.currentTimeMillis() + 3_000;
        while (gate.getPendingRequests().isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        assertThat(gate.getPendingRequests()).isNotEmpty();

        String requestId = gate.getPendingRequests().getFirst().requestId();
        service.approve(requestId);

        future.get(2, TimeUnit.SECONDS);

        // The wrapped action (TrackingBehavior.action) was invoked
        assertThat(executed).isTrue();
    }
}