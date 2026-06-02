package dev.agenor.runtime.knowledge;

import dev.agenor.core.knowledge.KnowledgeDocument;
import dev.agenor.core.knowledge.KnowledgeStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.*;

class InMemoryKnowledgeStoreTest {

    enum Category { RETURNS, SHIPPING, BILLING }

    private KnowledgeStore<Category> store;

    @BeforeEach
    void setUp() {
        store = new InMemoryKnowledgeStore<>();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private KnowledgeDocument<Category> doc(String id, String title, String content,
                                             Category cat, String... keywords) {
        return new KnowledgeDocument<>(id, title, content, cat, Set.of(keywords));
    }

    // =========================================================================
    // add / getById
    // =========================================================================

    @Nested
    @DisplayName("add and getById")
    class AddAndGetById {

        @Test
        @DisplayName("getById returns empty when store is empty")
        void emptyStoreReturnsEmpty() {
            assertThat(store.getById("missing")).isEmpty();
        }

        @Test
        @DisplayName("getById returns document after add")
        void getByIdAfterAdd() {
            store.add(doc("d1", "Title", "Content", Category.RETURNS, "return"));
            assertThat(store.getById("d1")).isPresent()
                .get().extracting(KnowledgeDocument::id).isEqualTo("d1");
        }

        @Test
        @DisplayName("add replaces document with same id")
        void addReplacesExistingId() {
            store.add(doc("d1", "Original", "Content", Category.RETURNS));
            store.add(doc("d1", "Updated",  "Content", Category.RETURNS));
            assertThat(store.getById("d1")).isPresent()
                .get().extracting(KnowledgeDocument::title).isEqualTo("Updated");
        }

        @Test
        @DisplayName("size reflects number of distinct documents")
        void sizeReflectsDistinctDocuments() {
            assertThat(store.size()).isZero();
            store.add(doc("d1", "A", "C", Category.RETURNS));
            store.add(doc("d2", "B", "C", Category.SHIPPING));
            assertThat(store.size()).isEqualTo(2);
            store.add(doc("d1", "A replaced", "C", Category.RETURNS));
            assertThat(store.size()).isEqualTo(2); // no duplicate
        }
    }

    // =========================================================================
    // search
    // =========================================================================

    @Nested
    @DisplayName("search")
    class Search {

        @BeforeEach
        void populate() {
            store.add(doc("r1", "Return Policy",  "Return items within 30 days.", Category.RETURNS,  "return", "refund"));
            store.add(doc("s1", "Shipping Guide", "Standard shipping takes 5 days.", Category.SHIPPING, "shipping", "delivery"));
            store.add(doc("b1", "Billing FAQ",    "Invoice questions answered here.", Category.BILLING,  "invoice", "billing"));
        }

        @Test
        @DisplayName("returns documents matching query keyword")
        void matchesQueryKeyword() {
            List<KnowledgeDocument<Category>> results = store.search("refund", 5);
            assertThat(results).extracting(KnowledgeDocument::id).contains("r1");
        }

        @Test
        @DisplayName("returns empty list for unrelated query")
        void emptyForUnrelatedQuery() {
            assertThat(store.search("zxqwerty", 5)).isEmpty();
        }

        @Test
        @DisplayName("respects topK limit")
        void respectsTopKLimit() {
            // all 3 docs have some generic word in content
            store.add(doc("x1", "Extra 1", "days items policy", Category.BILLING));
            store.add(doc("x2", "Extra 2", "days items return", Category.BILLING));
            assertThat(store.search("days", 2)).hasSizeLessThanOrEqualTo(2);
        }

        @Test
        @DisplayName("results are ordered by descending relevance")
        void orderedByDescendingRelevance() {
            // r1 has both title and keyword match for "return"; s1 has none
            List<KnowledgeDocument<Category>> results = store.search("return refund", 5);
            assertThat(results.get(0).id()).isEqualTo("r1");
        }

        @Test
        @DisplayName("documents with zero score are excluded")
        void zeroScoreDocumentsExcluded() {
            List<KnowledgeDocument<Category>> results = store.search("refund", 5);
            // shipping and billing docs should not appear
            assertThat(results).extracting(KnowledgeDocument::id)
                .doesNotContain("s1", "b1");
        }
    }

    // =========================================================================
    // searchByCategory
    // =========================================================================

    @Nested
    @DisplayName("searchByCategory")
    class SearchByCategory {

        @BeforeEach
        void populate() {
            store.add(doc("r1", "Return Policy",   "How to return items.", Category.RETURNS,  "return"));
            store.add(doc("r2", "Refund Timeline", "Refund within 5 days.", Category.RETURNS, "refund", "return"));
            store.add(doc("s1", "Shipping Times",  "Return address for shipping.", Category.SHIPPING, "shipping"));
        }

        @Test
        @DisplayName("returns only documents in the specified category")
        void onlyDocumentsInCategory() {
            List<KnowledgeDocument<Category>> results =
                store.searchByCategory("return", Category.RETURNS, 10);
            assertThat(results).extracting(KnowledgeDocument::id)
                .containsExactlyInAnyOrder("r1", "r2")
                .doesNotContain("s1");
        }

        @Test
        @DisplayName("returns empty list when category has no matching documents")
        void emptyWhenNoCategoryMatch() {
            assertThat(store.searchByCategory("return", Category.BILLING, 10)).isEmpty();
        }

        @Test
        @DisplayName("respects topK within category")
        void respectsTopKWithinCategory() {
            assertThat(store.searchByCategory("return", Category.RETURNS, 1)).hasSize(1);
        }
    }

    // =========================================================================
    // getByCategory
    // =========================================================================

    @Nested
    @DisplayName("getByCategory")
    class GetByCategory {

        @Test
        @DisplayName("returns all documents in category regardless of query")
        void returnsAllInCategory() {
            store.add(doc("r1", "Return Policy",   "Content.", Category.RETURNS));
            store.add(doc("r2", "Refund Timeline", "Content.", Category.RETURNS));
            store.add(doc("s1", "Shipping Guide",  "Content.", Category.SHIPPING));

            assertThat(store.getByCategory(Category.RETURNS))
                .extracting(KnowledgeDocument::id)
                .containsExactlyInAnyOrder("r1", "r2");
        }

        @Test
        @DisplayName("returns empty list for category with no documents")
        void emptyForMissingCategory() {
            assertThat(store.getByCategory(Category.BILLING)).isEmpty();
        }
    }
}
