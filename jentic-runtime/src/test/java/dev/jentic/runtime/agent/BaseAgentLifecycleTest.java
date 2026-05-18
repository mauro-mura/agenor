package dev.jentic.runtime.agent;

import dev.jentic.core.AgentStatus;
import dev.jentic.runtime.directory.InMemoryAgentDirectory;
import dev.jentic.core.telemetry.JenticTelemetry;
import dev.jentic.runtime.messaging.InMemoryMessageDispatcher;
import dev.jentic.runtime.scheduler.SimpleBehaviorScheduler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for BaseAgent lifecycle with status tracking
 */
class BaseAgentLifecycleTest {
    
    private TestAgent agent;
    
    @BeforeEach
    void setUp() {
        agent = new TestAgent("test-agent", "Test Agent");
        agent.setMessageDispatcher(new InMemoryMessageDispatcher(new InMemoryAgentDirectory(), JenticTelemetry.noop()));
        agent.setAgentDirectory(new InMemoryAgentDirectory());
        agent.setBehaviorScheduler(new SimpleBehaviorScheduler());
    }
    
    @Test
    void shouldTrackStatusDuringLifecycle() {
        // Initially stopped
        assertThat(agent.getStatus()).isEqualTo(AgentStatus.STOPPED);
        assertThat(agent.isRunning()).isFalse();
        
        // Start agent
        agent.start().join();
        
        assertThat(agent.getStatus()).isEqualTo(AgentStatus.RUNNING);
        assertThat(agent.isRunning()).isTrue();
        
        // Stop agent
        agent.stop().join();
        
        assertThat(agent.getStatus()).isEqualTo(AgentStatus.STOPPED);
        assertThat(agent.isRunning()).isFalse();
    }
    
    @Test
    void shouldHandleStartupFailure() {
        // Given
        FailingStartAgent failingAgent = new FailingStartAgent("failing-agent");
        failingAgent.setMessageDispatcher(new InMemoryMessageDispatcher(new InMemoryAgentDirectory(), JenticTelemetry.noop()));
        
        // When
        assertThatThrownBy(() -> failingAgent.start().join())
            .isInstanceOf(RuntimeException.class)
            .hasMessageContaining("Failed to start agent");
        
        // Then
        assertThat(failingAgent.getStatus()).isEqualTo(AgentStatus.ERROR);
        assertThat(failingAgent.isRunning()).isFalse();
    }
    
    @Test
    void shouldHandleShutdownError() {
        // Given
        FailingStopAgent failingAgent = new FailingStopAgent("failing-stop-agent");
        failingAgent.setMessageDispatcher(new InMemoryMessageDispatcher(new InMemoryAgentDirectory(), JenticTelemetry.noop()));
        
        // Start successfully
        failingAgent.start().join();
        assertThat(failingAgent.getStatus()).isEqualTo(AgentStatus.RUNNING);
        
        // When - stop fails but should still mark as stopped
        failingAgent.stop().join(); // Should not throw
        
        // Then - should be marked as error but not throw exception
        assertThat(failingAgent.getStatus()).isEqualTo(AgentStatus.ERROR);
        assertThat(failingAgent.isRunning()).isFalse();
    }
    
    @Test
    void shouldNotStartTwice() {
        // Given
        agent.start().join();
        assertThat(agent.getStatus()).isEqualTo(AgentStatus.RUNNING);
        
        // When
        agent.start().join(); // Second start
        
        // Then
        assertThat(agent.getStatus()).isEqualTo(AgentStatus.RUNNING);
        assertThat(agent.isRunning()).isTrue();
    }
    
    @Test
    void shouldNotStopTwice() {
        // Given
        agent.start().join();
        agent.stop().join();
        assertThat(agent.getStatus()).isEqualTo(AgentStatus.STOPPED);
        
        // When
        agent.stop().join(); // Second stop
        
        // Then
        assertThat(agent.getStatus()).isEqualTo(AgentStatus.STOPPED);
        assertThat(agent.isRunning()).isFalse();
    }
    
    // Test agent implementations
    
    static class TestAgent extends BaseAgent {
        TestAgent(String agentId, String agentName) {
            super(agentId, agentName);
        }
    }
    
    static class FailingStartAgent extends BaseAgent {
        FailingStartAgent(String agentId) {
            super(agentId, agentId);
        }
        
        @Override
        protected void onStart() {
            throw new RuntimeException("Simulated startup failure");
        }
    }
    
    static class FailingStopAgent extends BaseAgent {
        FailingStopAgent(String agentId) {
            super(agentId, agentId);
        }
        
        @Override
        protected void onStop() {
            throw new RuntimeException("Simulated shutdown failure");
        }
    }
}