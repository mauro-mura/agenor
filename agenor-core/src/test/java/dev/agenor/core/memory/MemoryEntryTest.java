package dev.agenor.core.memory;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.*;

@DisplayName("MemoryEntry Tests")
class MemoryEntryTest {

    @Test
    @DisplayName("Should create entry with builder")
    void testBuilderCreation() {
        Instant now = Instant.now();

        MemoryEntry entry = MemoryEntry.builder("Test content")
            .ownerId("agent-1")
            .metadata("key1", "value1")
            .metadata("key2", 42)
            .createdAt(now)
            .expiresAt(now.plus(Duration.ofHours(1)))
            .sharedWith("agent-2", "agent-3")
            .build();

        assertThat(entry.content()).isEqualTo("Test content");
        assertThat(entry.ownerId()).isEqualTo("agent-1");
        assertThat(entry.metadata()).containsEntry("key1", "value1");
        assertThat(entry.metadata()).containsEntry("key2", 42);
        assertThat(entry.createdAt()).isEqualTo(now);
        assertThat(entry.expiresAt()).isEqualTo(now.plus(Duration.ofHours(1)));
        assertThat(entry.sharedWith()).containsExactlyInAnyOrder("agent-2", "agent-3");
    }

    @Test
    @DisplayName("Should create minimal entry")
    void testMinimalEntry() {
        MemoryEntry entry = MemoryEntry.builder("Content").build();

        assertThat(entry.content()).isEqualTo("Content");
        assertThat(entry.metadata()).isEmpty();
        assertThat(entry.sharedWith()).isEmpty();
        assertThat(entry.ownerId()).isNull();
        assertThat(entry.expiresAt()).isNull();
        assertThat(entry.createdAt()).isNotNull();
    }

    @Test
    @DisplayName("Should reject null content")
    void testNullContent() {
        assertThatThrownBy(() -> MemoryEntry.builder(null).build())
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("Content cannot be null");
    }

    @Test
    @DisplayName("Should reject blank content")
    void testBlankContent() {
        assertThatThrownBy(() -> MemoryEntry.builder("   ").build())
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Content cannot be blank");
    }

    @Test
    @DisplayName("Should reject expiration before creation")
    void testInvalidExpiration() {
        Instant now = Instant.now();

        assertThatThrownBy(() ->
            MemoryEntry.builder("Content")
                .createdAt(now)
                .expiresAt(now.minus(Duration.ofHours(1)))
                .build()
        ).isInstanceOf(IllegalArgumentException.class)
         .hasMessageContaining("Expiration time cannot be before creation time");
    }

    @Test
    @DisplayName("Should check if expired")
    void testIsExpired() {
        Instant now = Instant.now();

        // Not expired - future expiration
        MemoryEntry notExpired = MemoryEntry.builder("Content")
            .expiresAt(now.plus(Duration.ofHours(1)))
            .build();
        assertThat(notExpired.isExpired()).isFalse();

        // Expired - past expiration
        MemoryEntry expired = MemoryEntry.builder("Content")
            .createdAt(now.minus(Duration.ofHours(2)))
            .expiresAt(now.minus(Duration.ofHours(1)))
            .build();
        assertThat(expired.isExpired()).isTrue();

        // Never expires - null expiration
        MemoryEntry neverExpires = MemoryEntry.builder("Content").build();
        assertThat(neverExpires.isExpired()).isFalse();
    }

    @Test
    @DisplayName("Should check ownership")
    void testOwnership() {
        MemoryEntry entry = MemoryEntry.builder("Content")
            .ownerId("agent-1")
            .build();

        assertThat(entry.isOwnedBy("agent-1")).isTrue();
        assertThat(entry.isOwnedBy("agent-2")).isFalse();
        assertThat(entry.isOwnedBy(null)).isFalse();
    }

    @Test
    @DisplayName("Should check shared access")
    void testSharedAccess() {
        MemoryEntry entry = MemoryEntry.builder("Content")
            .ownerId("agent-1")
            .sharedWith("agent-2", "agent-3")
            .build();

        assertThat(entry.isSharedWith("agent-2")).isTrue();
        assertThat(entry.isSharedWith("agent-3")).isTrue();
        assertThat(entry.isSharedWith("agent-4")).isFalse();
    }

    @Test
    @DisplayName("Should check can access")
    void testCanAccess() {
        MemoryEntry entry = MemoryEntry.builder("Content")
            .ownerId("agent-1")
            .sharedWith("agent-2")
            .build();

        // Owner can access
        assertThat(entry.canAccess("agent-1")).isTrue();

        // Shared agent can access
        assertThat(entry.canAccess("agent-2")).isTrue();

        // Other agent cannot access
        assertThat(entry.canAccess("agent-3")).isFalse();
    }

    @Test
    @DisplayName("Should get typed metadata")
    void testTypedMetadata() {
        MemoryEntry entry = MemoryEntry.builder("Content")
            .metadata("stringKey", "value")
            .metadata("intKey", 42)
            .metadata("boolKey", true)
            .build();

        assertThat(entry.getMetadata("stringKey", String.class)).isEqualTo("value");
        assertThat(entry.getMetadata("intKey", Integer.class)).isEqualTo(42);
        assertThat(entry.getMetadata("boolKey", Boolean.class)).isTrue();
        assertThat(entry.getMetadata("missing", String.class)).isNull();
    }

    @Test
    @DisplayName("Should be immutable")
    void testImmutability() {
        Map<String, Object> metadata = Map.of("key", "value");
        Set<String> sharedWith = Set.of("agent-2");

        MemoryEntry entry = MemoryEntry.builder("Content")
            .metadata(metadata)
            .sharedWith(sharedWith)
            .build();

        // Returned collections should be immutable
        assertThatThrownBy(() -> entry.metadata().put("new", "value"))
            .isInstanceOf(UnsupportedOperationException.class);

        assertThatThrownBy(() -> entry.sharedWith().add("agent-3"))
            .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("Should support multiple metadata entries at once")
    void testBulkMetadata() {
        Map<String, Object> metadata = Map.of(
            "key1", "value1",
            "key2", 42,
            "key3", true
        );

        MemoryEntry entry = MemoryEntry.builder("Content")
            .metadata(metadata)
            .build();

        assertThat(entry.metadata()).containsAllEntriesOf(metadata);
    }

    @Test
    @DisplayName("Should support shared with collection")
    void testSharedWithCollection() {
        Set<String> agents = Set.of("agent-2", "agent-3", "agent-4");

        MemoryEntry entry = MemoryEntry.builder("Content")
            .sharedWith(agents)
            .build();

        assertThat(entry.sharedWith()).containsExactlyInAnyOrderElementsOf(agents);
    }
}
