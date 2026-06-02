package dev.agenor.core.condition;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.util.Map;

import static org.assertj.core.api.Assertions.*;

/**
 * Comprehensive test coverage for ConditionContext class
 */
@DisplayName("ConditionContext Tests")
class ConditionContextTest {

    private ConditionContext context;

    @BeforeEach
    void setUp() {
        context = new ConditionContext();
    }

    @Test
    @DisplayName("should store and retrieve string value")
    void shouldStoreAndRetrieveStringValue() {
        // Given
        String key = "user.name";
        String value = "admin";

        // When
        context.set(key, value);
        String retrieved = context.get(key, String.class);

        // Then
        assertThat(retrieved).isEqualTo(value);
    }

    @Test
    @DisplayName("should store and retrieve numeric values")
    void shouldStoreAndRetrieveNumericValues() {
        // Given
        context.set("cpu.usage", 75.5);
        context.set("max.threads", 100);
        context.set("memory.bytes", 1024L);

        // When/Then
        assertThat(context.get("cpu.usage", Double.class)).isEqualTo(75.5);
        assertThat(context.get("max.threads", Integer.class)).isEqualTo(100);
        assertThat(context.get("memory.bytes", Long.class)).isEqualTo(1024L);
    }

    @Test
    @DisplayName("should store and retrieve boolean values")
    void shouldStoreAndRetrieveBooleanValues() {
        // Given
        context.set("feature.enabled", true);
        context.set("debug.mode", false);

        // When/Then
        assertThat(context.get("feature.enabled", Boolean.class)).isTrue();
        assertThat(context.get("debug.mode", Boolean.class)).isFalse();
    }

    @Test
    @DisplayName("should store and retrieve complex objects")
    void shouldStoreAndRetrieveComplexObjects() {
        // Given
        TestMetrics metrics = new TestMetrics(80.0, 1024);
        context.set("system.metrics", metrics);

        // When
        TestMetrics retrieved = context.get("system.metrics", TestMetrics.class);

        // Then
        assertThat(retrieved).isEqualTo(metrics);
        assertThat(retrieved.cpu()).isEqualTo(80.0);
        assertThat(retrieved.memory()).isEqualTo(1024);
    }

    @Test
    @DisplayName("should return null for non-existent key")
    void shouldReturnNullForNonExistentKey() {
        // When
        String value = context.get("non.existent", String.class);

        // Then
        assertThat(value).isNull();
    }

    @Test
    @DisplayName("should return null for non-existent key even if other keys exist")
    void shouldReturnNullForNonExistentKeyEvenIfOtherKeysExist() {
        // Given
        context.set("existing.key", "value");

        // When
        Object value = context.get("non.existent.key", Object.class);

        // Then
        assertThat(value).isNull();
    }

    @Test
    @DisplayName("should replace existing value")
    void shouldReplaceExistingValue() {
        // Given
        context.set("counter", 1);

        // When
        context.set("counter", 2);
        Integer newValue = context.get("counter", Integer.class);

        // Then
        assertThat(newValue).isEqualTo(2);
    }

    @Test
    @DisplayName("should return default value when key not present")
    void shouldReturnDefaultValueWhenKeyNotPresent() {
        // When
        int value = context.getOrDefault("missing.key", 42);

        // Then
        assertThat(value).isEqualTo(42);
    }

    @Test
    @DisplayName("should return actual value when key exists")
    void shouldReturnActualValueWhenKeyExists() {
        // Given
        context.set("existing.key", 100);

        // When
        int value = context.getOrDefault("existing.key", 42);

        // Then
        assertThat(value).isEqualTo(100);
    }

    @Test
    @DisplayName("should return default when key does not exist")
    void shouldReturnDefaultWhenKeyDoesNotExist() {
        // When
        String value = context.getOrDefault("non.existent.key", "default");

        // Then
        assertThat(value).isEqualTo("default");
    }

    @Test
    @DisplayName("should handle getOrDefault with various types")
    void shouldHandleGetOrDefaultWithVariousTypes() {
        // Given
        context.set("existing.int", 100);
        context.set("existing.boolean", true);

        // When/Then - existing keys return actual values
        assertThat(context.getOrDefault("existing.int", 42)).isEqualTo(100);
        assertThat(context.getOrDefault("existing.boolean", false)).isTrue();

        // When/Then - missing keys return defaults
        assertThat(context.getOrDefault("missing.int", 42)).isEqualTo(42);
        assertThat(context.getOrDefault("missing.string", "default")).isEqualTo("default");
    }

    @Test
    @DisplayName("should return true when key exists")
    void shouldReturnTrueWhenKeyExists() {
        // Given
        context.set("existing.key", "value");

        // When/Then
        assertThat(context.has("existing.key")).isTrue();
    }

    @Test
    @DisplayName("should return false when key does not exist")
    void shouldReturnFalseWhenKeyDoesNotExist() {
        // When/Then
        assertThat(context.has("non.existent.key")).isFalse();
    }

    @Test
    @DisplayName("should distinguish between missing key and existing key")
    void shouldDistinguishBetweenMissingAndExistingKey() {
        // Given
        context.set("existing.key", "value");

        // When/Then
        assertThat(context.has("existing.key")).isTrue();
        assertThat(context.has("missing.key")).isFalse();
    }

    @Test
    @DisplayName("should clear all properties")
    void shouldClearAllProperties() {
        // Given
        context.set("key1", "value1");
        context.set("key2", "value2");
        context.set("key3", 123);

        // When
        context.clear();

        // Then
        assertThat(context.has("key1")).isFalse();
        assertThat(context.has("key2")).isFalse();
        assertThat(context.has("key3")).isFalse();
    }

    @Test
    @DisplayName("should allow adding properties after clear")
    void shouldAllowAddingPropertiesAfterClear() {
        // Given
        context.set("old.key", "old");
        context.clear();

        // When
        context.set("new.key", "new");

        // Then
        assertThat(context.has("old.key")).isFalse();
        assertThat(context.has("new.key")).isTrue();
        assertThat(context.get("new.key", String.class)).isEqualTo("new");
    }

    @Test
    @DisplayName("should return immutable map with all properties")
    void shouldReturnImmutableMapWithAllProperties() {
        // Given
        context.set("key1", "value1");
        context.set("key2", 42);
        context.set("key3", true);

        // When
        Map<String, Object> map = context.asMap();

        // Then
        assertThat(map).hasSize(3);
        assertThat(map).containsEntry("key1", "value1");
        assertThat(map).containsEntry("key2", 42);
        assertThat(map).containsEntry("key3", true);
    }

    @Test
    @DisplayName("should return empty map when context is empty")
    void shouldReturnEmptyMapWhenContextIsEmpty() {
        // When
        Map<String, Object> map = context.asMap();

        // Then
        assertThat(map).isEmpty();
    }

    @Test
    @DisplayName("should return snapshot that doesn't reflect subsequent changes")
    void shouldReturnSnapshotThatDoesntReflectSubsequentChanges() {
        // Given
        context.set("key1", "value1");
        Map<String, Object> snapshot = context.asMap();

        // When
        context.set("key2", "value2");

        // Then
        assertThat(snapshot).hasSize(1);
        assertThat(snapshot).containsEntry("key1", "value1");
        assertThat(snapshot).doesNotContainKey("key2");
    }

    @Test
    @DisplayName("returned map should be immutable")
    void returnedMapShouldBeImmutable() {
        // Given
        context.set("key", "value");
        Map<String, Object> map = context.asMap();

        // When/Then
        assertThatThrownBy(() -> map.put("new", "value"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("should handle concurrent access to different keys")
    void shouldHandleConcurrentAccessToDifferentKeys() throws InterruptedException {
        // Given
        int threadCount = 10;
        Thread[] threads = new Thread[threadCount];

        // When
        for (int i = 0; i < threadCount; i++) {
            final int index = i;
            threads[i] = new Thread(() -> context.set("key" + index, "value" + index));
            threads[i].start();
        }

        for (Thread thread : threads) {
            thread.join();
        }

        // Then
        for (int i = 0; i < threadCount; i++) {
            assertThat(context.get("key" + i, String.class)).isEqualTo("value" + i);
        }
    }

    @Test
    @DisplayName("should handle type casting correctly")
    void shouldHandleTypeCastingCorrectly() {
        // Given
        context.set("number", 42);

        // When
        Integer asInteger = context.get("number", Integer.class);
        Object asObject = context.get("number", Object.class);

        // Then
        assertThat(asInteger).isEqualTo(42);
        assertThat(asObject).isEqualTo(42);
    }

    @Test
    @DisplayName("should demonstrate unchecked cast behavior")
    void shouldDemonstrateUncheckedCastBehavior() {
        // Given
        context.set("number", 42);

        // When/Then - ClassCastException happens when trying to use a mismatched type
        assertThatThrownBy(() -> {
            String wrongType = context.get("number", String.class);
            wrongType.length(); // This line triggers the ClassCastException
        }).isInstanceOf(ClassCastException.class);
    }

    // Helper record for testing
    record TestMetrics(double cpu, int memory) {}
}
