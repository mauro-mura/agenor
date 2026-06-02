package dev.agenor.core.composite;

import dev.agenor.core.Agent;
import dev.agenor.core.Behavior;
import dev.agenor.core.BehaviorType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("CompositeBehavior Additional Edge Cases")
class CompositeBehaviorEdgeCasesTest {

    @Mock
    private Agent mockAgent;

    private TestCompositeBehavior compositeBehavior;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        compositeBehavior = new TestCompositeBehavior("test-composite");
    }

    @Test
    @DisplayName("Should handle null agent gracefully")
    void shouldHandleNullAgent() {
        // When
        compositeBehavior.setAgent(null);

        // Then
        assertThat(compositeBehavior.getAgent()).isNull();
    }

    @Test
    @DisplayName("Should propagate null agent to child behaviors")
    void shouldPropagateNullAgentToChildren() {
        // Given
        TestCompositeBehavior childComposite = new TestCompositeBehavior("child");
        compositeBehavior.addChildBehavior(childComposite);
        compositeBehavior.setAgent(mockAgent);

        // When
        compositeBehavior.setAgent(null);

        // Then
        assertThat(childComposite.getAgent()).isNull();
    }

    @Test
    @DisplayName("Should handle empty child behaviors list")
    void shouldHandleEmptyChildBehaviorsList() {
        // When
        compositeBehavior.stop();

        // Then
        assertThat(compositeBehavior.isActive()).isFalse();
        assertThat(compositeBehavior.getChildBehaviors()).isEmpty();
    }

    @Test
    @DisplayName("Should add child behavior after agent is set")
    void shouldAddChildBehaviorAfterAgentIsSet() {
        // Given
        compositeBehavior.setAgent(mockAgent);
        TestCompositeBehavior childComposite = new TestCompositeBehavior("child");

        // When
        compositeBehavior.addChildBehavior(childComposite);

        // Then
        assertThat(childComposite.getAgent()).isEqualTo(mockAgent);
    }

    @Test
    @DisplayName("Should not propagate agent to non-composite child behaviors")
    void shouldNotPropagateAgentToNonCompositeChildren() {
        // Given
        Behavior mockChild = mock(Behavior.class);
        when(mockChild.getBehaviorId()).thenReturn("mock-child");

        // When
        compositeBehavior.addChildBehavior(mockChild);
        compositeBehavior.setAgent(mockAgent);

        // Then
        verify(mockChild, never()).getAgent();
    }

    @Test
    @DisplayName("Should handle multiple levels of nested composite behaviors")
    void shouldHandleMultipleLevelsOfNesting() {
        // Given
        TestCompositeBehavior level1 = new TestCompositeBehavior("level1");
        TestCompositeBehavior level2 = new TestCompositeBehavior("level2");
        TestCompositeBehavior level3 = new TestCompositeBehavior("level3");

        compositeBehavior.addChildBehavior(level1);
        level1.addChildBehavior(level2);
        level2.addChildBehavior(level3);

        // When
        compositeBehavior.setAgent(mockAgent);

        // Then
        assertThat(compositeBehavior.getAgent()).isEqualTo(mockAgent);
        assertThat(level1.getAgent()).isEqualTo(mockAgent);
        assertThat(level2.getAgent()).isEqualTo(mockAgent);
        assertThat(level3.getAgent()).isEqualTo(mockAgent);
    }

    @Test
    @DisplayName("Should stop nested composite behaviors recursively")
    void shouldStopNestedCompositeBehaviorsRecursively() {
        // Given
        TestCompositeBehavior level1 = new TestCompositeBehavior("level1");
        TestCompositeBehavior level2 = new TestCompositeBehavior("level2");
        Behavior mockLeaf = mock(Behavior.class);

        compositeBehavior.addChildBehavior(level1);
        level1.addChildBehavior(level2);
        level2.addChildBehavior(mockLeaf);

        // When
        compositeBehavior.stop();

        // Then
        assertThat(compositeBehavior.isActive()).isFalse();
        assertThat(level1.isActive()).isFalse();
        assertThat(level2.isActive()).isFalse();
        verify(mockLeaf).stop();
    }

    @Test
    @DisplayName("Should maintain active state before stop is called")
    void shouldMaintainActiveStateBeforeStop() {
        // Given
        compositeBehavior.addChildBehavior(new TestCompositeBehavior("child"));

        // Then
        assertThat(compositeBehavior.isActive()).isTrue();
    }

    @Test
    @DisplayName("Should allow re-adding same child behavior")
    void shouldAllowReaddingSameChildBehavior() {
        // Given
        Behavior child = createMockBehavior("child");

        // When
        compositeBehavior.addChildBehavior(child);
        compositeBehavior.addChildBehavior(child);

        // Then
        assertThat(compositeBehavior.getChildBehaviors()).hasSize(2);
        assertThat(compositeBehavior.getChildBehaviors()).containsExactly(child, child);
    }

    @Test
    @DisplayName("Should handle adding multiple child behaviors in sequence")
    void shouldHandleAddingMultipleChildBehaviorsInSequence() {
        // Given
        Behavior child1 = createMockBehavior("child1");
        Behavior child2 = createMockBehavior("child2");
        Behavior child3 = createMockBehavior("child3");

        // When
        compositeBehavior.addChildBehavior(child1);
        compositeBehavior.addChildBehavior(child2);
        compositeBehavior.addChildBehavior(child3);

        // Then
        assertThat(compositeBehavior.getChildBehaviors())
            .hasSize(3)
            .containsExactly(child1, child2, child3);
    }

    @Test
    @DisplayName("Should preserve child behavior order")
    void shouldPreserveChildBehaviorOrder() {
        // Given
        Behavior child1 = createMockBehavior("child1");
        Behavior child2 = createMockBehavior("child2");
        Behavior child3 = createMockBehavior("child3");

        // When
        compositeBehavior.addChildBehavior(child1);
        compositeBehavior.addChildBehavior(child2);
        compositeBehavior.addChildBehavior(child3);

        // Then
        var children = compositeBehavior.getChildBehaviors();
        assertThat(children.get(0)).isEqualTo(child1);
        assertThat(children.get(1)).isEqualTo(child2);
        assertThat(children.get(2)).isEqualTo(child3);
    }

    @Test
    @DisplayName("Should handle stop when already stopped")
    void shouldHandleStopWhenAlreadyStopped() {
        // Given
        Behavior child = createMockBehavior("child");
        compositeBehavior.addChildBehavior(child);
        compositeBehavior.stop();

        // When
        compositeBehavior.stop();

        // Then
        assertThat(compositeBehavior.isActive()).isFalse();
        verify(child, times(2)).stop();
    }

    @Test
    @DisplayName("Should return correct behavior ID after construction")
    void shouldReturnCorrectBehaviorIdAfterConstruction() {
        // Given
        String expectedId = "custom-test-id";
        TestCompositeBehavior behavior = new TestCompositeBehavior(expectedId);

        // Then
        assertThat(behavior.getBehaviorId()).isEqualTo(expectedId);
    }

    @Test
    @DisplayName("Should handle agent update for existing children")
    void shouldHandleAgentUpdateForExistingChildren() {
        // Given
        TestCompositeBehavior child1 = new TestCompositeBehavior("child1");
        TestCompositeBehavior child2 = new TestCompositeBehavior("child2");
        compositeBehavior.addChildBehavior(child1);
        compositeBehavior.addChildBehavior(child2);
        compositeBehavior.setAgent(mockAgent);

        // When
        Agent newAgent = mock(Agent.class);
        compositeBehavior.setAgent(newAgent);

        // Then
        assertThat(child1.getAgent()).isEqualTo(newAgent);
        assertThat(child2.getAgent()).isEqualTo(newAgent);
    }

    @Test
    @DisplayName("Should maintain child behaviors after stop")
    void shouldMaintainChildBehaviorsAfterStop() {
        // Given
        Behavior child1 = createMockBehavior("child1");
        Behavior child2 = createMockBehavior("child2");
        compositeBehavior.addChildBehavior(child1);
        compositeBehavior.addChildBehavior(child2);

        // When
        compositeBehavior.stop();

        // Then
        assertThat(compositeBehavior.getChildBehaviors()).hasSize(2);
    }

    @Test
    @DisplayName("Should handle mixed composite and regular child behaviors")
    void shouldHandleMixedChildBehaviors() {
        // Given
        TestCompositeBehavior compositeChild = new TestCompositeBehavior("composite");
        Behavior regularChild = createMockBehavior("regular");

        // When
        compositeBehavior.addChildBehavior(compositeChild);
        compositeBehavior.addChildBehavior(regularChild);
        compositeBehavior.setAgent(mockAgent);

        // Then
        assertThat(compositeBehavior.getAgent()).isEqualTo(mockAgent);
        assertThat(compositeChild.getAgent()).isEqualTo(mockAgent);
        assertThat(compositeBehavior.getChildBehaviors()).hasSize(2);
    }

    // Helper methods

    private Behavior createMockBehavior(String id) {
        Behavior behavior = mock(Behavior.class);
        when(behavior.getBehaviorId()).thenReturn(id);
        when(behavior.isActive()).thenReturn(true);
        when(behavior.execute()).thenReturn(CompletableFuture.completedFuture(null));
        return behavior;
    }

    // Test implementation of abstract CompositeBehavior
    private static class TestCompositeBehavior extends CompositeBehavior {

        TestCompositeBehavior(String behaviorId) {
            super(behaviorId);
        }

        @Override
        public BehaviorType getType() {
            return BehaviorType.CUSTOM;
        }

        @Override
        public CompletableFuture<Void> execute() {
            return CompletableFuture.completedFuture(null);
        }
    }
}
