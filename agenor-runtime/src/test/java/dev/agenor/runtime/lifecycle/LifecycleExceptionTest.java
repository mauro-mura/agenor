package dev.agenor.runtime.lifecycle;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class LifecycleExceptionTest {

    @Test
    void constructor_twoArgs_shouldSetAgentIdAndMessage() {
        LifecycleException ex = new LifecycleException("agent-1", "something went wrong");

        assertThat(ex.getAgentId()).isEqualTo("agent-1");
        assertThat(ex.getMessage()).contains("agent-1");
        assertThat(ex.getMessage()).contains("something went wrong");
    }

    @Test
    void constructor_threeArgs_shouldSetCause() {
        RuntimeException cause = new RuntimeException("root cause");
        LifecycleException ex = new LifecycleException("agent-2", "lifecycle failure", cause);

        assertThat(ex.getAgentId()).isEqualTo("agent-2");
        assertThat(ex.getCause()).isSameAs(cause);
        assertThat(ex.getMessage()).contains("agent-2");
        assertThat(ex.getMessage()).contains("lifecycle failure");
    }

    @Test
    void getMessage_shouldFormatWithAgentId() {
        LifecycleException ex = new LifecycleException("my-agent", "test error");

        String msg = ex.getMessage();
        assertThat(msg).isEqualTo("Agent 'my-agent': test error");
    }

    @Test
    void shouldBeRuntimeException() {
        LifecycleException ex = new LifecycleException("agent", "error");
        assertThat(ex).isInstanceOf(RuntimeException.class);
    }
}
