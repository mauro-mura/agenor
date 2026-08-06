package dev.agenor.runtime.behavior.composite;

import dev.agenor.core.Agent;
import dev.agenor.core.Behavior;
import dev.agenor.core.BehaviorType;
import dev.agenor.core.composite.CompositeBehavior;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("Composite Behavior Base Tests")
class CompositeBehaviorTest {

    @Mock
    private Agent mockAgent;

    private TestCompositeBehavior compositeBehavior;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        compositeBehavior = new TestCompositeBehavior("test-composite");
    }

    @Test
    @DisplayName("Should add child behaviors")
    void shouldAddChildBehaviors() {
        // Given
        Behavior child1 = createMockBehavior("child1");
        Behavior child2 = createMockBehavior("child2");

        // When
        compositeBehavior.addChildBehavior(child1);
        compositeBehavior.addChildBehavior(child2);

        // Then
        assertThat(compositeBehavior.getChildBehaviors()).hasSize(2);
        assertThat(compositeBehavior.getChildBehaviors()).containsExactly(child1, child2);
    }

    @Test
    @DisplayName("Should propagate agent to child behaviors")
    void shouldPropagateAgentToChildren() {
        // Given
        TestCompositeBehavior childComposite = new TestCompositeBehavior("child");
        compositeBehavior.addChildBehavior(childComposite);

        // When
        compositeBehavior.setAgent(mockAgent);

        // Then
        assertThat(compositeBehavior.getAgent()).isEqualTo(mockAgent);
        assertThat(childComposite.getAgent()).isEqualTo(mockAgent);
    }

    @Test
    @DisplayName("Should stop all child behaviors")
    void shouldStopAllChildren() {
        // Given
        Behavior child1 = createMockBehavior("child1");
        Behavior child2 = createMockBehavior("child2");
        compositeBehavior.addChildBehavior(child1);
        compositeBehavior.addChildBehavior(child2);

        // When
        compositeBehavior.stop();

        // Then
        assertThat(compositeBehavior.isActive()).isFalse();
        verify(child1).stop();
        verify(child2).stop();
    }

    @Test
    @DisplayName("Should return null interval for composite behaviors")
    void shouldReturnNullInterval() {
        assertThat(compositeBehavior.getInterval()).isNull();
    }

    @Test
    @DisplayName("Should return immutable list of child behaviors")
    void shouldReturnImmutableChildList() {
        // Given
        Behavior child = createMockBehavior("child");
        compositeBehavior.addChildBehavior(child);

        // When / Then
        assertThatThrownBy(() ->
            compositeBehavior.getChildBehaviors().add(createMockBehavior("new"))
        ).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("Should have correct behavior ID")
    void shouldHaveCorrectBehaviorId() {
        assertThat(compositeBehavior.getBehaviorId()).isEqualTo("test-composite");
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
