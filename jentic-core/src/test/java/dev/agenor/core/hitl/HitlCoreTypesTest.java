package dev.agenor.core.hitl;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

@DisplayName("dev.agenor.core.hitl — base types")
class HitlCoreTypesTest {

    // =========================================================================
    // ApprovalRequest
    // =========================================================================

    @Nested
    @DisplayName("ApprovalRequest")
    class ApprovalRequestTests {

        @Test
        @DisplayName("of() generates non-null UUID requestId")
        void of_generatesRequestId() {
            var req = ApprovalRequest.of("agent-1", "delete-record", "payload", Duration.ofMinutes(10));
            assertThat(req.requestId()).isNotNull().isNotBlank();
        }

        @Test
        @DisplayName("of() sets expiresAt in the future")
        void of_expiresAtInFuture() {
            var req = ApprovalRequest.of("agent-1", "delete-record", "payload", Duration.ofMinutes(10));
            assertThat(req.expiresAt()).isAfter(Instant.now());
        }

        @Test
        @DisplayName("of() with metadata stores metadata as unmodifiable")
        void of_withMetadata_unmodifiable() {
            var meta = Map.<String, Object>of("env", "prod");
            var req = ApprovalRequest.of("agent-1", "action", "payload", Duration.ofSeconds(5), meta);
            assertThat(req.metadata()).containsEntry("env", "prod");
            assertThatThrownBy(() -> req.metadata().put("x", "y"))
                    .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        @DisplayName("of() with no metadata returns empty map")
        void of_noMetadata_emptyMap() {
            var req = ApprovalRequest.of("agent-1", "action", "payload", Duration.ofSeconds(5));
            assertThat(req.metadata()).isEmpty();
        }

        @Test
        @DisplayName("two of() calls produce distinct requestIds")
        void of_distinctRequestIds() {
            var r1 = ApprovalRequest.of("a", "act", "p", Duration.ofMinutes(1));
            var r2 = ApprovalRequest.of("a", "act", "p", Duration.ofMinutes(1));
            assertThat(r1.requestId()).isNotEqualTo(r2.requestId());
        }

        @Test
        @DisplayName("null agentId throws IllegalArgumentException")
        void nullAgentId_throws() {
            assertThatThrownBy(() -> ApprovalRequest.of(null, "act", "p", Duration.ofMinutes(1)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("agentId");
        }

        @Test
        @DisplayName("blank action throws IllegalArgumentException")
        void blankAction_throws() {
            assertThatThrownBy(() -> ApprovalRequest.of("agent", " ", "p", Duration.ofMinutes(1)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("action");
        }
    }

    // =========================================================================
    // ApprovalDecision
    // =========================================================================

    @Nested
    @DisplayName("ApprovalDecision")
    class ApprovalDecisionTests {

        @Test
        @DisplayName("Approved is an ApprovalDecision")
        void approved_isApprovalDecision() {
            ApprovalDecision d = new ApprovalDecision.Approved();
            assertThat(d).isInstanceOf(ApprovalDecision.Approved.class);
        }

        @Test
        @DisplayName("Rejected stores reason")
        void rejected_storesReason() {
            var d = new ApprovalDecision.Rejected("too risky");
            assertThat(d.reason()).isEqualTo("too risky");
        }

        @Test
        @DisplayName("Rejected null reason throws IllegalArgumentException")
        void rejected_nullReason_throws() {
            assertThatThrownBy(() -> new ApprovalDecision.Rejected(null))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("Modified stores newPayload")
        void modified_storesPayload() {
            var d = new ApprovalDecision.Modified("revised-payload");
            assertThat(d.newPayload()).isEqualTo("revised-payload");
        }

        @Test
        @DisplayName("Modified null newPayload throws IllegalArgumentException")
        void modified_nullPayload_throws() {
            assertThatThrownBy(() -> new ApprovalDecision.Modified(null))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("exhaustive switch covers all permits")
        void exhaustiveSwitch_allCases() {
            ApprovalDecision[] decisions = {
                new ApprovalDecision.Approved(),
                new ApprovalDecision.Rejected("reason"),
                new ApprovalDecision.Modified("payload")
            };
            for (ApprovalDecision d : decisions) {
                String tag = switch (d) {
                    case ApprovalDecision.Approved  a -> "approved";
                    case ApprovalDecision.Rejected  r -> "rejected";
                    case ApprovalDecision.Modified  m -> "modified";
                };
                assertThat(tag).isNotNull();
            }
        }
    }

    // =========================================================================
    // ApprovalTimeoutException
    // =========================================================================

    @Nested
    @DisplayName("ApprovalTimeoutException")
    class ApprovalTimeoutExceptionTests {

        @Test
        @DisplayName("stores requestId and expiresAt from direct constructor")
        void storesFields_directConstructor() {
            var expiresAt = Instant.now().plusSeconds(60);
            var ex = new ApprovalTimeoutException("req-123", expiresAt);
            assertThat(ex.getRequestId()).isEqualTo("req-123");
            assertThat(ex.getExpiresAt()).isEqualTo(expiresAt);
        }

        @Test
        @DisplayName("stores requestId and expiresAt from ApprovalRequest constructor")
        void storesFields_requestConstructor() {
            var req = ApprovalRequest.of("agent-1", "action", "p", Duration.ofMinutes(5));
            var ex = new ApprovalTimeoutException(req);
            assertThat(ex.getRequestId()).isEqualTo(req.requestId());
            assertThat(ex.getExpiresAt()).isEqualTo(req.expiresAt());
        }

        @Test
        @DisplayName("message contains requestId and expiresAt")
        void message_containsFields() {
            var ex = new ApprovalTimeoutException("req-xyz", Instant.EPOCH);
            assertThat(ex.getMessage())
                    .contains("req-xyz")
                    .contains(Instant.EPOCH.toString());
        }

        @Test
        @DisplayName("is unchecked (extends JenticException / RuntimeException)")
        void isUnchecked() {
            var ex = new ApprovalTimeoutException("id", Instant.now());
            assertThat(ex).isInstanceOf(RuntimeException.class);
        }
    }

    // =========================================================================
    // DefaultApprovalNotifier sentinel
    // =========================================================================

    @Nested
    @DisplayName("DefaultApprovalNotifier sentinel")
    class DefaultApprovalNotifierTests {

        @Test
        @DisplayName("cannot be instantiated — constructor throws UnsupportedOperationException")
        void cannotBeInstantiated() {
            assertThatThrownBy(() -> {
                var c = DefaultApprovalNotifier.class.getDeclaredConstructor();
                c.setAccessible(true);
                c.newInstance();
            }).hasCauseInstanceOf(UnsupportedOperationException.class);
        }
    }

    // =========================================================================
    // RequiresApproval annotation
    // =========================================================================

    @Nested
    @DisplayName("@RequiresApproval annotation")
    class RequiresApprovalTests {

        @RequiresApproval
        private static class DefaultBehavior {}

        @RequiresApproval(timeout = "5m", notifier = NoOpNotifier.class)
        private static class CustomBehavior {}

        public static class NoOpNotifier implements ApprovalNotifier {
            @Override public void notify(ApprovalRequest request) {}
        }

        @Test
        @DisplayName("default timeout is '30m'")
        void defaultTimeout() {
            var ann = DefaultBehavior.class.getAnnotation(RequiresApproval.class);
            assertThat(ann.timeout()).isEqualTo("30m");
        }

        @Test
        @DisplayName("default notifier is DefaultApprovalNotifier sentinel")
        void defaultNotifier() {
            var ann = DefaultBehavior.class.getAnnotation(RequiresApproval.class);
            assertThat(ann.notifier()).isEqualTo(DefaultApprovalNotifier.class);
        }

        @Test
        @DisplayName("custom timeout and notifier are stored correctly")
        void customValues() {
            var ann = CustomBehavior.class.getAnnotation(RequiresApproval.class);
            assertThat(ann.timeout()).isEqualTo("5m");
            assertThat(ann.notifier()).isEqualTo(NoOpNotifier.class);
        }
    }
}
