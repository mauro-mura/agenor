package dev.jentic.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

import java.util.Set;

/**
 * Unit tests for AgentQuery record and builder
 */
class AgentQueryTest {
    
    @Test
    @DisplayName("Should create query by type")
    void shouldCreateQueryByType() {
        // When
        AgentQuery query = AgentQuery.byType("test-type");
        
        // Then
        assertThat(query.agentType()).isEqualTo("test-type");
        assertThat(query.requiredCapabilities()).isNull();
        assertThat(query.status()).isNull();
    }
    
    @Test
    @DisplayName("Should create query by status")
    void shouldCreateQueryByStatus() {
        // When
        AgentQuery query = AgentQuery.byStatus(AgentStatus.RUNNING);
        
        // Then
        assertThat(query.status()).isEqualTo(AgentStatus.RUNNING);
        assertThat(query.agentType()).isNull();
        assertThat(query.requiredCapabilities()).isNull();
    }
    
    @Test
    @DisplayName("Should create query with capabilities")
    void shouldCreateQueryWithCapabilities() {
        // Given
        Set<String> capabilities = Set.of("cap1", "cap2");
        
        // When
        AgentQuery query = AgentQuery.withCapabilities(capabilities);
        
        // Then
        assertThat(query.requiredCapabilities()).isEqualTo(capabilities);
        assertThat(query.agentType()).isNull();
        assertThat(query.status()).isNull();
    }
    
    @Test
    @DisplayName("Should add capabilities individually")
    void shouldAddCapabilitiesIndividually() {
        // When
        AgentQuery query = AgentQuery.builder()
            .requiredCapability("cap1")
            .requiredCapability("cap2")
            .build();
        
        // Then
        assertThat(query.requiredCapabilities())
            .containsExactlyInAnyOrder("cap1", "cap2");
    }
    
    @Test
    @DisplayName("Should handle requiredCapability() when capabilities already set")
    void shouldAddCapabilityToExistingSet() {
        // Given - builder with pre-existing capabilities
        Set<String> initialCapabilities = Set.of("cap1", "cap2");

        // When - add additional capability
        AgentQuery query = AgentQuery.builder()
                .requiredCapabilities(initialCapabilities)
                .requiredCapability("cap3")
                .build();

        // Then - all three capabilities present
        assertThat(query.requiredCapabilities())
                .containsExactlyInAnyOrder("cap1", "cap2", "cap3");
    }

    @Test
    @DisplayName("Should handle multiple requiredCapability() calls with existing set")
    void shouldChainMultipleCapabilitiesToExistingSet() {
        // Given
        Set<String> initial = Set.of("existing");

        // When
        AgentQuery query = AgentQuery.builder()
                .requiredCapabilities(initial)
                .requiredCapability("new1")
                .requiredCapability("new2")
                .requiredCapability("new3")
                .build();

        // Then
        assertThat(query.requiredCapabilities())
                .containsExactlyInAnyOrder("existing", "new1", "new2", "new3");
    }

    @Test
    @DisplayName("Should overwrite capabilities when requiredCapabilities() called after requiredCapability()")
    void shouldOverwriteCapabilities() {
        // Given
        Set<String> newSet = Set.of("cap3", "cap4");

        // When - set individual capability first, then overwrite
        AgentQuery query = AgentQuery.builder()
                .requiredCapability("cap1")
                .requiredCapability("cap2")
                .requiredCapabilities(newSet)
                .build();

        // Then - only new set is present
        assertThat(query.requiredCapabilities())
                .containsExactlyInAnyOrder("cap3", "cap4");
    }

    @Test
    @DisplayName("Should create query with all fields null")
    void shouldCreateEmptyQuery() {
        // When
        AgentQuery query = AgentQuery.builder().build();

        // Then
        assertThat(query.agentType()).isNull();
        assertThat(query.requiredCapabilities()).isNull();
        assertThat(query.status()).isNull();
    }

    @Test
    @DisplayName("Should handle single capability via requiredCapability()")
    void shouldHandleSingleCapability() {
        // When
        AgentQuery query = AgentQuery.builder()
                .requiredCapability("single-cap")
                .build();

        // Then
        assertThat(query.requiredCapabilities())
                .containsExactly("single-cap");
    }
}