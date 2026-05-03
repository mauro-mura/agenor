package dev.jentic.runtime.condition;

import dev.jentic.core.Agent;
import dev.jentic.core.AgentStatus;
import dev.jentic.core.Behavior;
import dev.jentic.core.MessageService;
import dev.jentic.core.condition.Condition;
import dev.jentic.runtime.agent.BaseAgent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for AgentCondition pre-built conditions
 */
class AgentConditionTest {
    
    private TestAgent runningAgent;
    private TestAgent stoppedAgent;
    
    @BeforeEach
    void setUp() {
        runningAgent = new TestAgent("running-agent", "Running Agent");
        runningAgent.start().join();
        
        stoppedAgent = new TestAgent("stopped-agent", "Stopped Agent");
    }
    
    @Test
    void shouldCheckIfAgentIsRunning() {
        // Given
        Condition condition = AgentCondition.isRunning();
        
        // When/Then
        assertThat(condition.evaluate(runningAgent)).isTrue();
        assertThat(condition.evaluate(stoppedAgent)).isFalse();
    }
    
    @Test
    void shouldCheckIfAgentHasStatus() {
        // Given
        Condition activeCondition = AgentCondition.hasStatus(AgentStatus.RUNNING);
        Condition idleCondition = AgentCondition.hasStatus(AgentStatus.SUSPENDED);
        
        // When/Then
        assertThat(activeCondition.evaluate(runningAgent)).isTrue();
        assertThat(idleCondition.evaluate(runningAgent)).isFalse();
    }
    
    @Test
    void shouldCheckIdMatches() {
        // Given
        Condition exactMatch = AgentCondition.idMatches("running-agent");
        Condition patternMatch = AgentCondition.idMatches("running-.*");
        Condition noMatch = AgentCondition.idMatches("other-.*");
        
        // When/Then
        assertThat(exactMatch.evaluate(runningAgent)).isTrue();
        assertThat(patternMatch.evaluate(runningAgent)).isTrue();
        assertThat(noMatch.evaluate(runningAgent)).isFalse();
    }
    
    @Test
    void shouldCheckNameContains() {
        // Given
        Condition containsRunning = AgentCondition.nameContains("Running");
        Condition containsAgent = AgentCondition.nameContains("Agent");
        Condition notContains = AgentCondition.nameContains("NotFound");
        
        // When/Then
        assertThat(containsRunning.evaluate(runningAgent)).isTrue();
        assertThat(containsAgent.evaluate(runningAgent)).isTrue();
        assertThat(notContains.evaluate(runningAgent)).isFalse();
    }
    
    @Test
    void shouldCombineAgentConditions() {
        // Given
        Condition combined = AgentCondition.isRunning()
            .and(AgentCondition.nameContains("Running"));
        
        // When/Then
        assertThat(combined.evaluate(runningAgent)).isTrue();
        assertThat(combined.evaluate(stoppedAgent)).isFalse();
    }
    
    @Test
    void shouldHandleNonBaseAgentForStatus() {
        // Given
        Agent nonBaseAgent = new Agent() {
            @Override
            public String getAgentId() { return "non-base"; }

            @Override
            public String getAgentName() { return "Non-Base Agent"; }

            @Override
            public CompletableFuture<Void> start() {
                return null;
            }

            @Override
            public CompletableFuture<Void> stop() {
                return null;
            }

            @Override
            public boolean isRunning() { return true; }

            @Override
            public void addBehavior(Behavior behavior) {

            }

            @Override
            public void removeBehavior(String behaviorId) {

            }

            @Override
            public dev.jentic.core.messaging.MessageDispatcher getMessageDispatcher() {
                return null;
            }
        };

        Condition condition = AgentCondition.hasStatus(AgentStatus.RUNNING);

        // When/Then
        assertThat(condition.evaluate(nonBaseAgent)).isFalse();
    }
    
    static class TestAgent extends BaseAgent {
        TestAgent(String id, String name) {
            super(id, name);
        }
    }
}