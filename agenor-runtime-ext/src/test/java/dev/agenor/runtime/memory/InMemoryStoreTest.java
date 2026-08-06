package dev.agenor.runtime.memory;

import dev.agenor.core.memory.*;
import org.junit.jupiter.api.*;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;

@DisplayName("InMemoryStore Tests")
class InMemoryStoreTest {

    private InMemoryStore store;

    @BeforeEach
    void setUp() {
        store = new InMemoryStore();
    }

    @AfterEach
    void tearDown() {
        store.shutdown();
    }

    @Test
    @DisplayName("Should store and retrieve entry")
    void testStoreAndRetrieve() {
        // Given
        MemoryEntry entry = MemoryEntry.builder("Test content")
            .ownerId("agent-1")
            .build();

        // When
        store.store("key-1", entry, MemoryScope.SHORT_TERM).join();
        Optional<MemoryEntry> retrieved = store.retrieve("key-1", MemoryScope.SHORT_TERM).join();

        // Then
        assertThat(retrieved).isPresent();
        assertThat(retrieved.get().content()).isEqualTo("Test content");
        assertThat(retrieved.get().ownerId()).isEqualTo("agent-1");
    }

    @Test
    @DisplayName("Should return empty for non-existent key")
    void testRetrieveNonExistent() {
        Optional<MemoryEntry> retrieved = store.retrieve("missing", MemoryScope.SHORT_TERM).join();

        assertThat(retrieved).isEmpty();
    }

    @Test
    @DisplayName("Should store in different scopes")
    void testDifferentScopes() {
        MemoryEntry shortEntry = MemoryEntry.builder("Short term").build();
        MemoryEntry longEntry = MemoryEntry.builder("Long term").build();

        store.store("key-1", shortEntry, MemoryScope.SHORT_TERM).join();
        store.store("key-1", longEntry, MemoryScope.LONG_TERM).join();

        Optional<MemoryEntry> shortRetrieved = store.retrieve("key-1", MemoryScope.SHORT_TERM).join();
        Optional<MemoryEntry> longRetrieved = store.retrieve("key-1", MemoryScope.LONG_TERM).join();

        assertThat(shortRetrieved.get().content()).isEqualTo("Short term");
        assertThat(longRetrieved.get().content()).isEqualTo("Long term");
    }

    @Test
    @DisplayName("Should replace existing entry")
    void testReplaceEntry() {
        MemoryEntry entry1 = MemoryEntry.builder("Original").build();
        MemoryEntry entry2 = MemoryEntry.builder("Updated").build();

        store.store("key-1", entry1, MemoryScope.SHORT_TERM).join();
        store.store("key-1", entry2, MemoryScope.SHORT_TERM).join();

        Optional<MemoryEntry> retrieved = store.retrieve("key-1", MemoryScope.SHORT_TERM).join();

        assertThat(retrieved.get().content()).isEqualTo("Updated");
    }

    @Test
    @DisplayName("Should delete entry")
    void testDelete() {
        MemoryEntry entry = MemoryEntry.builder("Content").build();

        store.store("key-1", entry, MemoryScope.SHORT_TERM).join();
        store.delete("key-1", MemoryScope.SHORT_TERM).join();

        Optional<MemoryEntry> retrieved = store.retrieve("key-1", MemoryScope.SHORT_TERM).join();
        assertThat(retrieved).isEmpty();
    }

    @Test
    @DisplayName("Should handle delete of non-existent key")
    void testDeleteNonExistent() {
        // Should not throw exception
        assertThatNoException().isThrownBy(() ->
            store.delete("missing", MemoryScope.SHORT_TERM).join()
        );
    }

    @Test
    @DisplayName("Should clear all entries in scope")
    void testClear() {
        store.store("key-1", MemoryEntry.builder("One").build(), MemoryScope.SHORT_TERM).join();
        store.store("key-2", MemoryEntry.builder("Two").build(), MemoryScope.SHORT_TERM).join();
        store.store("key-3", MemoryEntry.builder("Three").build(), MemoryScope.LONG_TERM).join();

        store.clear(MemoryScope.SHORT_TERM).join();

        assertThat(store.retrieve("key-1", MemoryScope.SHORT_TERM).join()).isEmpty();
        assertThat(store.retrieve("key-2", MemoryScope.SHORT_TERM).join()).isEmpty();
        assertThat(store.retrieve("key-3", MemoryScope.LONG_TERM).join()).isPresent();
    }

    @Test
    @DisplayName("Should list all keys in scope")
    void testListKeys() {
        store.store("key-1", MemoryEntry.builder("One").build(), MemoryScope.SHORT_TERM).join();
        store.store("key-2", MemoryEntry.builder("Two").build(), MemoryScope.SHORT_TERM).join();
        store.store("key-3", MemoryEntry.builder("Three").build(), MemoryScope.LONG_TERM).join();

        List<String> shortTermKeys = store.listKeys(MemoryScope.SHORT_TERM).join();
        List<String> longTermKeys = store.listKeys(MemoryScope.LONG_TERM).join();

        assertThat(shortTermKeys).containsExactlyInAnyOrder("key-1", "key-2");
        assertThat(longTermKeys).containsExactly("key-3");
    }

    @Test
    @DisplayName("Should check if entry exists")
    void testExists() {
        store.store("key-1", MemoryEntry.builder("Content").build(), MemoryScope.SHORT_TERM).join();

        assertThat(store.exists("key-1", MemoryScope.SHORT_TERM).join()).isTrue();
        assertThat(store.exists("missing", MemoryScope.SHORT_TERM).join()).isFalse();
    }

    @Test
    @DisplayName("Should search by text")
    void testSearchByText() {
        store.store("k1", MemoryEntry.builder("Hello world").build(), MemoryScope.SHORT_TERM).join();
        store.store("k2", MemoryEntry.builder("Hello there").build(), MemoryScope.SHORT_TERM).join();
        store.store("k3", MemoryEntry.builder("Goodbye").build(), MemoryScope.SHORT_TERM).join();

        MemoryQuery query = MemoryQuery.builder()
            .text("hello")
            .build();

        List<MemoryEntry> results = store.search(query).join();

        assertThat(results).hasSize(2);
        assertThat(results).allMatch(e -> e.content().toLowerCase().contains("hello"));
    }

    @Test
    @DisplayName("Should search by owner")
    void testSearchByOwner() {
        store.store("k1", MemoryEntry.builder("One").ownerId("agent-1").build(),
                   MemoryScope.SHORT_TERM).join();
        store.store("k2", MemoryEntry.builder("Two").ownerId("agent-1").build(),
                   MemoryScope.SHORT_TERM).join();
        store.store("k3", MemoryEntry.builder("Three").ownerId("agent-2").build(),
                   MemoryScope.SHORT_TERM).join();

        MemoryQuery query = MemoryQuery.builder()
            .ownerId("agent-1")
            .build();

        List<MemoryEntry> results = store.search(query).join();

        assertThat(results).hasSize(2);
        assertThat(results).allMatch(e -> e.ownerId().equals("agent-1"));
    }

    @Test
    @DisplayName("Should search by metadata")
    void testSearchByMetadata() {
        store.store("k1", MemoryEntry.builder("One")
            .metadata("status", "active").build(), MemoryScope.SHORT_TERM).join();
        store.store("k2", MemoryEntry.builder("Two")
            .metadata("status", "active").build(), MemoryScope.SHORT_TERM).join();
        store.store("k3", MemoryEntry.builder("Three")
            .metadata("status", "inactive").build(), MemoryScope.SHORT_TERM).join();

        MemoryQuery query = MemoryQuery.builder()
            .filter("status", "active")
            .build();

        List<MemoryEntry> results = store.search(query).join();

        assertThat(results).hasSize(2);
    }

    @Test
    @DisplayName("Should respect search limit")
    void testSearchLimit() {
        for (int i = 0; i < 20; i++) {
            store.store("key-" + i, MemoryEntry.builder("Content " + i).build(),
                       MemoryScope.SHORT_TERM).join();
        }

        MemoryQuery query = MemoryQuery.builder()
            .limit(5)
            .build();

        List<MemoryEntry> results = store.search(query).join();

        assertThat(results).hasSize(5);
    }

    @Test
    @DisplayName("Should filter expired entries")
    void testExpiredEntries() {
        Instant now = Instant.now();

        MemoryEntry expired = MemoryEntry.builder("Expired")
            .createdAt(now.minus(Duration.ofHours(2)))
            .expiresAt(now.minus(Duration.ofHours(1)))
            .build();

        store.store("expired", expired, MemoryScope.SHORT_TERM).join();

        Optional<MemoryEntry> retrieved = store.retrieve("expired", MemoryScope.SHORT_TERM).join();
        assertThat(retrieved).isEmpty();
    }

    @Test
    @DisplayName("Should not return expired entries in search")
    void testSearchExcludesExpired() {
        Instant now = Instant.now();

        store.store("k1", MemoryEntry.builder("Active").build(), MemoryScope.SHORT_TERM).join();
        store.store("k2", MemoryEntry.builder("Expired")
            .createdAt(now.minus(Duration.ofHours(2)))
            .expiresAt(now.minus(Duration.ofHours(1)))
            .build(), MemoryScope.SHORT_TERM).join();

        MemoryQuery query = MemoryQuery.builder().build();
        List<MemoryEntry> results = store.search(query).join();

        assertThat(results).hasSize(1);
        assertThat(results.get(0).content()).isEqualTo("Active");
    }

    @Test
    @DisplayName("Should provide statistics")
    void testGetStats() {
        store.store("s1", MemoryEntry.builder("Short 1").build(), MemoryScope.SHORT_TERM).join();
        store.store("s2", MemoryEntry.builder("Short 2").build(), MemoryScope.SHORT_TERM).join();
        store.store("l1", MemoryEntry.builder("Long 1").build(), MemoryScope.LONG_TERM).join();

        MemoryStats stats = store.getStats();

        assertThat(stats.shortTermCount()).isEqualTo(2);
        assertThat(stats.longTermCount()).isEqualTo(1);
        assertThat(stats.totalCount()).isEqualTo(3);
        assertThat(stats.estimatedTokens()).isGreaterThan(0);
        assertThat(stats.estimatedSizeBytes()).isGreaterThan(0);
    }

    @Test
    @DisplayName("Should validate keys")
    void testKeyValidation() {
        MemoryEntry entry = MemoryEntry.builder("Content").build();

        assertThatThrownBy(() ->
            store.store(null, entry, MemoryScope.SHORT_TERM).join()
        ).hasCauseInstanceOf(MemoryException.class);

        assertThatThrownBy(() ->
            store.store("", entry, MemoryScope.SHORT_TERM).join()
        ).hasCauseInstanceOf(MemoryException.class);

        assertThatThrownBy(() ->
            store.store("   ", entry, MemoryScope.SHORT_TERM).join()
        ).hasCauseInstanceOf(MemoryException.class);
    }

    @Test
    @DisplayName("Should enforce key length limit")
    void testKeyLengthLimit() {
        MemoryEntry entry = MemoryEntry.builder("Content").build();
        String longKey = "x".repeat(256);

        assertThatThrownBy(() ->
            store.store(longKey, entry, MemoryScope.SHORT_TERM).join()
        ).hasCauseInstanceOf(MemoryException.class)
         .hasMessageContaining("255 characters");
    }

    @Test
    @DisplayName("Should enforce quota")
    void testQuotaEnforcement() {
        InMemoryStore limitedStore = new InMemoryStore(2, 60);

        try {
            limitedStore.store("k1", MemoryEntry.builder("One").build(),
                              MemoryScope.SHORT_TERM).join();
            limitedStore.store("k2", MemoryEntry.builder("Two").build(),
                              MemoryScope.SHORT_TERM).join();

            assertThatThrownBy(() ->
                limitedStore.store("k3", MemoryEntry.builder("Three").build(),
                                  MemoryScope.SHORT_TERM).join()
            ).hasCauseInstanceOf(MemoryException.class)
             .hasMessageContaining("Maximum entries exceeded");
        } finally {
            limitedStore.shutdown();
        }
    }

    @Test
    @DisplayName("Should handle concurrent access")
    void testConcurrentAccess() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(10);
        CountDownLatch latch = new CountDownLatch(100);

        try {
            for (int i = 0; i < 100; i++) {
                final int index = i;
                executor.submit(() -> {
                    try {
                        store.store("key-" + index,
                                   MemoryEntry.builder("Content " + index).build(),
                                   MemoryScope.SHORT_TERM).join();
                    } finally {
                        latch.countDown();
                    }
                });
            }

            assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();

            MemoryStats stats = store.getStats();
            assertThat(stats.shortTermCount()).isEqualTo(100);
        } finally {
            executor.shutdown();
        }
    }

    @Test
    @DisplayName("Should get store name")
    void testGetStoreName() {
        assertThat(store.getStoreName()).isEqualTo("InMemoryStore");
    }
}
