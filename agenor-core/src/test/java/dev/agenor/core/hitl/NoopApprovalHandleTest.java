package dev.agenor.core.hitl;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifies the absent-module default used by {@code AgenorRuntime.getApprovalService()}
 * when no {@code HitlSupportProvider} (e.g. {@code agenor-runtime-ext}) is on the
 * classpath (ADR-027).
 */
@DisplayName("NoopApprovalHandle")
class NoopApprovalHandleTest {

    private final NoopApprovalHandle handle = new NoopApprovalHandle();

    @Test
    @DisplayName("getPendingRequests() returns an empty list rather than failing")
    void getPendingRequests_returnsEmpty() {
        assertThat(handle.getPendingRequests()).isEmpty();
    }

    @Test
    @DisplayName("approve() fails fast pointing at the missing module")
    void approve_throwsUnsupported() {
        assertThatThrownBy(() -> handle.approve("req-1"))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("agenor-runtime-ext");
    }

    @Test
    @DisplayName("reject() fails fast pointing at the missing module")
    void reject_throwsUnsupported() {
        assertThatThrownBy(() -> handle.reject("req-1", "reason"))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("agenor-runtime-ext");
    }

    @Test
    @DisplayName("modify() fails fast pointing at the missing module")
    void modify_throwsUnsupported() {
        assertThatThrownBy(() -> handle.modify("req-1", "new-payload"))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("agenor-runtime-ext");
    }

    @Test
    @DisplayName("submit() fails fast pointing at the missing module")
    void submit_throwsUnsupported() {
        assertThatThrownBy(() -> handle.submit("req-1", new ApprovalDecision.Approved()))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("agenor-runtime-ext");
    }
}
