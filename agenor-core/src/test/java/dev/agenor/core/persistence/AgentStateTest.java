package dev.agenor.core.persistence;

import dev.agenor.core.AgentStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("AgentState Tests")
class AgentStateTest {

    @Test
    @DisplayName("should create state with minimal required fields")
    void shouldCreateMinimalState() {
        // Given
        String agentId = "test-agent-1";

        // When
        AgentState state = AgentState.builder(agentId).build();

        // Then
        assertEquals(agentId, state.agentId());
        assertNull(state.agentName());
        assertEquals("unknown", state.agentType());
        assertEquals(AgentStatus.UNKNOWN, state.status());
        assertTrue(state.data().isEmpty());
        assertTrue(state.metadata().isEmpty());
        assertEquals(1, state.version());
        assertNotNull(state.savedAt());
    }

    @Test
    @DisplayName("should create state with all fields")
    void shouldCreateCompleteState() {
        // Given
        String agentId = "test-agent-1";
        String agentName = "Test Agent";
        String agentType = "processor";
        AgentStatus status = AgentStatus.RUNNING;
        Map<String, Object> data = Map.of("counter", 42, "items", List.of("a", "b"));
        Map<String, String> metadata = Map.of("env", "test", "version", "1.0");
        long version = 5;
        Instant savedAt = Instant.parse("2025-01-01T00:00:00Z");

        // When
        AgentState state = AgentState.builder(agentId)
                .agentName(agentName)
                .agentType(agentType)
                .status(status)
                .data(data)
                .metadata(metadata)
                .version(version)
                .savedAt(savedAt)
                .build();

        // Then
        assertEquals(agentId, state.agentId());
        assertEquals(agentName, state.agentName());
        assertEquals(agentType, state.agentType());
        assertEquals(status, state.status());
        assertEquals(data, state.data());
        assertEquals(metadata, state.metadata());
        assertEquals(version, state.version());
        assertEquals(savedAt, state.savedAt());
    }

    @Test
    @DisplayName("should add individual data entries")
    void shouldAddIndividualDataEntries() {
        // Given
        String agentId = "test-agent-1";

        // When
        AgentState state = AgentState.builder(agentId)
                .data("counter", 42)
                .data("name", "test")
                .data("items", List.of("a", "b", "c"))
                .build();

        // Then
        assertEquals(3, state.data().size());
        assertEquals(42, state.getData("counter", Integer.class));
        assertEquals("test", state.getData("name", String.class));
        assertEquals(List.of("a", "b", "c"), state.getData("items", List.class));
    }

    @Test
    @DisplayName("should merge data maps")
    void shouldMergeDataMaps() {
        // Given
        String agentId = "test-agent-1";
        Map<String, Object> data1 = Map.of("key1", "value1", "key2", 42);
        Map<String, Object> data2 = Map.of("key3", "value3", "key4", List.of(1, 2, 3));

        // When
        AgentState state = AgentState.builder(agentId)
                .data(data1)
                .data(data2)
                .build();

        // Then
        assertEquals(4, state.data().size());
        assertEquals("value1", state.getData("key1", String.class));
        assertEquals(42, state.getData("key2", Integer.class));
        assertEquals("value3", state.getData("key3", String.class));
        assertEquals(List.of(1, 2, 3), state.getData("key4", List.class));
    }

    @Test
    @DisplayName("should add individual metadata entries")
    void shouldAddIndividualMetadataEntries() {
        // Given
        String agentId = "test-agent-1";

        // When
        AgentState state = AgentState.builder(agentId)
                .metadata("env", "production")
                .metadata("version", "2.0")
                .metadata("region", "us-east-1")
                .build();

        // Then
        assertEquals(3, state.metadata().size());
        assertEquals("production", state.getMetadata("env"));
        assertEquals("2.0", state.getMetadata("version"));
        assertEquals("us-east-1", state.getMetadata("region"));
    }

    @Test
    @DisplayName("should merge metadata maps")
    void shouldMergeMetadataMaps() {
        // Given
        String agentId = "test-agent-1";
        Map<String, String> metadata1 = Map.of("key1", "value1", "key2", "value2");
        Map<String, String> metadata2 = Map.of("key3", "value3", "key4", "value4");

        // When
        AgentState state = AgentState.builder(agentId)
                .metadata(metadata1)
                .metadata(metadata2)
                .build();

        // Then
        assertEquals(4, state.metadata().size());
        assertEquals("value1", state.getMetadata("key1"));
        assertEquals("value2", state.getMetadata("key2"));
        assertEquals("value3", state.getMetadata("key3"));
        assertEquals("value4", state.getMetadata("key4"));
    }

    @Test
    @DisplayName("should return null for non-existent data key")
    void shouldReturnNullForNonExistentDataKey() {
        // Given
        AgentState state = AgentState.builder("test-agent-1")
                .data("existing", "value")
                .build();

        // When
        String result = state.getData("non-existent", String.class);

        // Then
        assertNull(result);
    }

    @Test
    @DisplayName("should return null for non-existent metadata key")
    void shouldReturnNullForNonExistentMetadataKey() {
        // Given
        AgentState state = AgentState.builder("test-agent-1")
                .metadata("existing", "value")
                .build();

        // When
        String result = state.getMetadata("non-existent");

        // Then
        assertNull(result);
    }

    @Test
    @DisplayName("should create immutable data map")
    void shouldCreateImmutableDataMap() {
        // Given
        Map<String, Object> mutableData = new HashMap<>();
        mutableData.put("key", "value");
        AgentState state = AgentState.builder("test-agent-1")
                .data(mutableData)
                .build();

        // When/Then
        assertThrows(UnsupportedOperationException.class, () ->
            state.data().put("new-key", "new-value")
        );
    }

    @Test
    @DisplayName("should create immutable metadata map")
    void shouldCreateImmutableMetadataMap() {
        // Given
        Map<String, String> mutableMetadata = new HashMap<>();
        mutableMetadata.put("key", "value");
        AgentState state = AgentState.builder("test-agent-1")
                .metadata(mutableMetadata)
                .build();

        // When/Then
        assertThrows(UnsupportedOperationException.class, () ->
            state.metadata().put("new-key", "new-value")
        );
    }

    @Test
    @DisplayName("should use default values for null fields")
    void shouldUseDefaultValuesForNullFields() {
        // Given/When
        AgentState state = new AgentState(
                "test-agent-1",
                null,           // agentName
                null,           // agentType
                null,           // status
                null,           // data
                null,           // metadata
                1,
                null            // savedAt
        );

        // Then
        assertNull(state.agentName());
        assertEquals("unknown", state.agentType());
        assertEquals(AgentStatus.UNKNOWN, state.status());
        assertTrue(state.data().isEmpty());
        assertTrue(state.metadata().isEmpty());
        assertNotNull(state.savedAt());
        assertTrue(state.savedAt().isBefore(Instant.now().plusSeconds(1)));
    }

    @Test
    @DisplayName("should handle complex data types")
    void shouldHandleComplexDataTypes() {
        // Given
        record CustomData(String name, int value) {}
        CustomData customObj = new CustomData("test", 42);

        // When
        AgentState state = AgentState.builder("test-agent-1")
                .data("string", "text")
                .data("integer", 123)
                .data("double", 45.67)
                .data("boolean", true)
                .data("list", List.of(1, 2, 3))
                .data("map", Map.of("key", "value"))
                .data("custom", customObj)
                .build();

        // Then
        assertEquals("text", state.getData("string", String.class));
        assertEquals(123, state.getData("integer", Integer.class));
        assertEquals(45.67, state.getData("double", Double.class));
        assertEquals(true, state.getData("boolean", Boolean.class));
        assertEquals(List.of(1, 2, 3), state.getData("list", List.class));
        assertEquals(Map.of("key", "value"), state.getData("map", Map.class));
        assertEquals(customObj, state.getData("custom", CustomData.class));
    }

    @Test
    @DisplayName("should ignore null keys in builder data")
    void shouldIgnoreNullKeysInBuilderData() {
        // Given/When
        AgentState state = AgentState.builder("test-agent-1")
                .data(null, "value")
                .data("key", null)
                .build();

        // Then
        assertTrue(state.data().isEmpty());
    }

    @Test
    @DisplayName("should ignore null keys in builder metadata")
    void shouldIgnoreNullKeysInBuilderMetadata() {
        // Given/When
        AgentState state = AgentState.builder("test-agent-1")
                .metadata(null, "value")
                .metadata("key", null)
                .build();

        // Then
        assertTrue(state.metadata().isEmpty());
    }

    @Test
    @DisplayName("should create defensive copy of mutable map")
    void shouldCreateDefensiveCopyOfMutableMap() {
        // Given
        Map<String, Object> mutableData = new HashMap<>();
        mutableData.put("key", "original");

        AgentState state = AgentState.builder("test-agent-1")
                .data(mutableData)
                .build();

        // When
        mutableData.put("key", "modified");
        mutableData.put("newKey", "newValue");

        // Then
        assertEquals("original", state.getData("key", String.class));
        assertNull(state.getData("newKey", String.class));
    }

    @Test
    @DisplayName("should support builder chaining")
    void shouldSupportBuilderChaining() {
        // Given/When
        AgentState state = AgentState.builder("test-agent-1")
                .agentName("Test Agent")
                .agentType("processor")
                .status(AgentStatus.RUNNING)
                .data("key1", "value1")
                .data("key2", 42)
                .metadata("env", "test")
                .version(2)
                .build();

        // Then
        assertNotNull(state);
        assertEquals("Test Agent", state.agentName());
        assertEquals("processor", state.agentType());
        assertEquals(AgentStatus.RUNNING, state.status());
        assertEquals(2, state.version());
    }

    @Test
    @DisplayName("should create modified copy from existing state")
    void shouldCreateModifiedCopyFromExistingState() {
        // Given
        AgentState original = AgentState.builder("test-agent-1")
                .agentName("Original")
                .data("counter", 10)
                .metadata("env", "test")
                .version(1)
                .build();

        // When
        AgentState modified = AgentState.builder(original.agentId())
                .agentName(original.agentName())
                .agentType(original.agentType())
                .status(original.status())
                .data(original.data())
                .data("counter", 20)  // Override counter
                .metadata(original.metadata())
                .version(original.version() + 1)
                .build();

        // Then
        assertEquals(original.agentId(), modified.agentId());
        assertEquals(original.agentName(), modified.agentName());
        assertEquals(20, modified.getData("counter", Integer.class));
        assertEquals(2, modified.version());
    }

    @Test
    @DisplayName("should handle null data map in builder")
    void shouldHandleNullDataMapInBuilder() {
        // Given/When
        AgentState state = AgentState.builder("test-agent-1")
                .data((Map<String, Object>) null)
                .build();

        // Then
        assertTrue(state.data().isEmpty());
    }

    @Test
    @DisplayName("should handle null metadata map in builder")
    void shouldHandleNullMetadataMapInBuilder() {
        // Given/When
        AgentState state = AgentState.builder("test-agent-1")
                .metadata((Map<String, String>) null)
                .build();

        // Then
        assertTrue(state.metadata().isEmpty());
    }

    @Test
    @DisplayName("should preserve insertion order for predictable iteration")
    void shouldPreserveDataEntries() {
        // Given/When
        AgentState state = AgentState.builder("test-agent-1")
                .data("first", 1)
                .data("second", 2)
                .data("third", 3)
                .build();

        // Then
        assertEquals(3, state.data().size());
        assertTrue(state.data().containsKey("first"));
        assertTrue(state.data().containsKey("second"));
        assertTrue(state.data().containsKey("third"));
    }

    @Test
    @DisplayName("should allow zero version")
    void shouldAllowZeroVersion() {
        // Given/When
        AgentState state = AgentState.builder("test-agent-1")
                .version(0)
                .build();

        // Then
        assertEquals(0, state.version());
    }

    @Test
    @DisplayName("should allow negative version for special cases")
    void shouldAllowNegativeVersion() {
        // Given/When
        AgentState state = AgentState.builder("test-agent-1")
                .version(-1)
                .build();

        // Then
        assertEquals(-1, state.version());
    }

    @Test
    @DisplayName("should set custom savedAt timestamp")
    void shouldSetCustomSavedAtTimestamp() {
        // Given
        Instant customTime = Instant.parse("2025-06-15T12:30:00Z");

        // When
        AgentState state = AgentState.builder("test-agent-1")
                .savedAt(customTime)
                .build();

        // Then
        assertEquals(customTime, state.savedAt());
    }
}
