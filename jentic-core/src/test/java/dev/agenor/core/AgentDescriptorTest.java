package dev.agenor.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

import java.time.Instant;
import java.util.Set;
import java.util.Map;

/**
 * Unit tests for AgentDescriptor record and builder
 */
class AgentDescriptorTest {

    @Test
    @DisplayName("Should create descriptor with builder")
    void shouldCreateDescriptorWithBuilder() {
        // Given
        String agentId = "test-agent";
        String agentName = "Test Agent";
        String agentType = "test-type";

        // When
        AgentDescriptor descriptor = AgentDescriptor.builder(agentId)
            .agentName(agentName)
            .agentType(agentType)
            .status(AgentStatus.RUNNING)
            .build();

        // Then
        assertThat(descriptor.agentId()).isEqualTo(agentId);
        assertThat(descriptor.agentName()).isEqualTo(agentName);
        assertThat(descriptor.agentType()).isEqualTo(agentType);
        assertThat(descriptor.status()).isEqualTo(AgentStatus.RUNNING);
        assertThat(descriptor.capabilities()).isEmpty();
        assertThat(descriptor.metadata()).isEmpty();
        assertThat(descriptor.registeredAt()).isNotNull();
        assertThat(descriptor.lastSeen()).isNotNull();
    }

    @Test
    @DisplayName("Should add capabilities")
    void shouldAddCapabilities() {
        // When
        AgentDescriptor descriptor = AgentDescriptor.builder("agent-1")
            .capability("cap1")
            .capability("cap2")
            .capabilities(Set.of("cap3", "cap4"))
            .build();

        // Then
        assertThat(descriptor.capabilities())
            .containsExactlyInAnyOrder("cap1", "cap2", "cap3", "cap4");
    }

    @Test
    @DisplayName("Should add metadata")
    void shouldAddMetadata() {
        // When
        AgentDescriptor descriptor = AgentDescriptor.builder("agent-1")
            .metadata("key1", "value1")
            .metadata("key2", "value2")
            .metadata(Map.of("key3", "value3", "key4", "value4"))
            .build();

        // Then
        assertThat(descriptor.metadata())
            .containsEntry("key1", "value1")
            .containsEntry("key2", "value2")
            .containsEntry("key3", "value3")
            .containsEntry("key4", "value4");
    }

    @Test
    @DisplayName("Should have default values")
    void shouldHaveDefaultValues() {
        // When
        AgentDescriptor descriptor = AgentDescriptor.builder("agent-1").build();

        // Then
        assertThat(descriptor.status()).isEqualTo(AgentStatus.UNKNOWN);
        assertThat(descriptor.capabilities()).isEmpty();
        assertThat(descriptor.metadata()).isEmpty();
        assertThat(descriptor.registeredAt()).isNotNull();
        assertThat(descriptor.lastSeen()).isNotNull();
    }

    @Test
    @DisplayName("Should have immutable collections")
    void shouldHaveImmutableCollections() {
        // Given
        AgentDescriptor descriptor = AgentDescriptor.builder("agent-1")
            .capability("test")
            .metadata("key", "value")
            .build();

        // When/Then
        assertThatThrownBy(() -> descriptor.capabilities().add("new-cap"))
            .isInstanceOf(UnsupportedOperationException.class);

        assertThatThrownBy(() -> descriptor.metadata().put("new-key", "new-value"))
            .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("Should handle null capability in capability() method")
    void shouldIgnoreNullCapability() {
        AgentDescriptor descriptor = AgentDescriptor.builder("agent-1")
                .capability("valid-cap")
                .capability(null)
                .build();

        assertThat(descriptor.capabilities())
                .containsExactly("valid-cap");
    }

    @Test
    @DisplayName("Should handle empty string capability")
    void shouldIgnoreEmptyCapability() {
        AgentDescriptor descriptor = AgentDescriptor.builder("agent-1")
                .capability("valid-cap")
                .capability("")
                .capability("   ")
                .build();

        assertThat(descriptor.capabilities())
                .containsExactly("valid-cap");
    }

    @Test
    @DisplayName("Should handle null capabilities set in capabilities() method")
    void shouldIgnoreNullCapabilitiesSet() {
        AgentDescriptor descriptor = AgentDescriptor.builder("agent-1")
                .capability("existing")
                .capabilities(null)
                .build();

        assertThat(descriptor.capabilities())
                .containsExactly("existing");
    }

    @Test
    @DisplayName("Should handle null metadata map")
    void shouldIgnoreNullMetadataMap() {
        AgentDescriptor descriptor = AgentDescriptor.builder("agent-1")
                .metadata("key1", "value1")
                .metadata((Map<String, String>) null)
                .build();

        assertThat(descriptor.metadata())
                .containsEntry("key1", "value1");
    }

    @Test
    @DisplayName("Should handle null key in metadata(key, value)")
    void shouldIgnoreNullMetadataKey() {
        AgentDescriptor descriptor = AgentDescriptor.builder("agent-1")
                .metadata("valid-key", "value")
                .metadata(null, "value")
                .build();

        assertThat(descriptor.metadata())
                .containsEntry("valid-key", "value")
                .hasSize(1);
    }

    @Test
    @DisplayName("Should handle null value in metadata(key, value)")
    void shouldIgnoreNullMetadataValue() {
        AgentDescriptor descriptor = AgentDescriptor.builder("agent-1")
                .metadata("valid-key", "value")
                .metadata("null-value", null)
                .build();

        assertThat(descriptor.metadata())
                .containsEntry("valid-key", "value")
                .hasSize(1);
    }

    @Test
    @DisplayName("Should handle null status in constructor")
    void shouldDefaultNullStatusToUnknown() {
        AgentDescriptor descriptor = new AgentDescriptor(
                "agent-1", "name", "type",
                null,  // null status
                Set.of("cap1"), Map.of("key", "value"), null,
                Instant.now(), Instant.now()
        );

        assertThat(descriptor.status()).isEqualTo(AgentStatus.UNKNOWN);
    }

    @Test
    @DisplayName("Should handle null capabilities in constructor")
    void shouldDefaultNullCapabilitiesToEmpty() {
        AgentDescriptor descriptor = new AgentDescriptor(
                "agent-1", "name", "type",
                AgentStatus.RUNNING,
                null,  // null capabilities
                Map.of("key", "value"), null,
                Instant.now(), Instant.now()
        );

        assertThat(descriptor.capabilities()).isEmpty();
    }

    @Test
    @DisplayName("Should handle null metadata in constructor")
    void shouldDefaultNullMetadataToEmpty() {
        AgentDescriptor descriptor = new AgentDescriptor(
                "agent-1", "name", "type",
                AgentStatus.RUNNING, Set.of("cap1"),
                null,  // null metadata
                null, Instant.now(), Instant.now()
        );

        assertThat(descriptor.metadata()).isEmpty();
    }

    @Test
    @DisplayName("Should handle null registeredAt in constructor")
    void shouldDefaultNullRegisteredAtToNow() {
        Instant before = Instant.now();

        AgentDescriptor descriptor = new AgentDescriptor(
                "agent-1", "name", "type",
                AgentStatus.RUNNING, Set.of("cap1"), Map.of("key", "value"),
                null,
                null,  // null registeredAt
                Instant.now()
        );

        Instant after = Instant.now();

        assertThat(descriptor.registeredAt())
                .isAfterOrEqualTo(before)
                .isBeforeOrEqualTo(after);
    }

    @Test
    @DisplayName("Should handle null lastSeen in constructor")
    void shouldDefaultNullLastSeenToNow() {
        Instant before = Instant.now();

        AgentDescriptor descriptor = new AgentDescriptor(
                "agent-1", "name", "type",
                AgentStatus.RUNNING, Set.of("cap1"), Map.of("key", "value"),
                null,
                Instant.now(),
                null  // null lastSeen
        );

        Instant after = Instant.now();

        assertThat(descriptor.lastSeen())
                .isAfterOrEqualTo(before)
                .isBeforeOrEqualTo(after);
    }

    @Test
    @DisplayName("Should trim whitespace from capabilities")
    void shouldTrimCapabilityWhitespace() {
        AgentDescriptor descriptor = AgentDescriptor.builder("agent-1")
                .capability("  trimmed-cap  ")
                .build();

        assertThat(descriptor.capabilities())
                .containsExactly("trimmed-cap");
    }

    @Test
    @DisplayName("Should merge capabilities from multiple sources")
    void shouldMergeCapabilities() {
        AgentDescriptor descriptor = AgentDescriptor.builder("agent-1")
                .capability("cap1")
                .capabilities(Set.of("cap2", "cap3"))
                .capability("cap4")
                .capabilities(Set.of("cap5"))
                .build();

        assertThat(descriptor.capabilities())
                .containsExactlyInAnyOrder("cap1", "cap2", "cap3", "cap4", "cap5");
    }

    @Test
    @DisplayName("Should merge metadata from multiple sources")
    void shouldMergeMetadata() {
        AgentDescriptor descriptor = AgentDescriptor.builder("agent-1")
                .metadata("k1", "v1")
                .metadata(Map.of("k2", "v2", "k3", "v3"))
                .metadata("k4", "v4")
                .build();

        assertThat(descriptor.metadata())
                .containsEntry("k1", "v1")
                .containsEntry("k2", "v2")
                .containsEntry("k3", "v3")
                .containsEntry("k4", "v4");
    }
}
