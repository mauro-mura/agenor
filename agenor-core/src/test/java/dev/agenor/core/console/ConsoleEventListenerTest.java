package dev.agenor.core.console;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ConsoleEventListenerTest {

    @Test
    void noOpShouldNotThrowOnAgentStarted() {
        ConsoleEventListener listener = ConsoleEventListener.noOp();

        assertDoesNotThrow(() ->
                listener.onAgentStarted("agent-1", "TestAgent")
        );

        assertDoesNotThrow(() ->
                listener.onAgentStarted("agent-2", "AnotherAgent")
        );
    }

    @Test
    void noOpShouldNotThrowOnAgentStopped() {
        ConsoleEventListener listener = ConsoleEventListener.noOp();

        assertDoesNotThrow(() ->
                listener.onAgentStopped("agent-1", "TestAgent")
        );

        assertDoesNotThrow(() ->
                listener.onAgentStopped("agent-2", "AnotherAgent")
        );
    }

    @Test
    void noOpShouldNotThrowOnMessageSent() {
        ConsoleEventListener listener = ConsoleEventListener.noOp();

        assertDoesNotThrow(() ->
                listener.onMessageSent("msg-1", "test.topic", "agent-1")
        );

        assertDoesNotThrow(() ->
                listener.onMessageSent("msg-2", "another.topic", "agent-2")
        );
    }

    @Test
    void noOpShouldNotThrowOnError() {
        ConsoleEventListener listener = ConsoleEventListener.noOp();

        assertDoesNotThrow(() ->
                listener.onError("agent-1", "Connection failed")
        );

        assertDoesNotThrow(() ->
                listener.onError("system", "Timeout occurred")
        );
    }

    @Test
    void noOpShouldNotThrowOnBehaviorExecuted() {
        ConsoleEventListener listener = ConsoleEventListener.noOp();

        assertDoesNotThrow(() ->
                listener.onBehaviorExecuted("agent-1", "behavior-1", 100L, true, null)
        );

        assertDoesNotThrow(() ->
                listener.onBehaviorExecuted("agent-1", "behavior-1", 50L, false, "Connection refused")
        );
    }

    @Test
    void noOpShouldReturnSameInstance() {
        ConsoleEventListener listener1 = ConsoleEventListener.noOp();
        ConsoleEventListener listener2 = ConsoleEventListener.noOp();

        assertNotNull(listener1);
        assertNotNull(listener2);
    }
}
