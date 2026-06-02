package dev.agenor.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link Page} and {@link PageRequest}.
 */
class PageTest {

    // -------------------------------------------------------------------------
    // Page
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Page stores content, totalElements, pageNumber and pageSize")
    void pageStoresFields() {
        var page = new Page<>(List.of("a", "b"), 10L, 2, 5);

        assertThat(page.content()).containsExactly("a", "b");
        assertThat(page.totalElements()).isEqualTo(10L);
        assertThat(page.pageNumber()).isEqualTo(2);
        assertThat(page.pageSize()).isEqualTo(5);
    }

    @Test
    @DisplayName("Page.empty() returns a page with empty content and zero total")
    void emptyPage() {
        var req = PageRequest.of(0, 20);
        Page<String> empty = Page.empty(req);

        assertThat(empty.content()).isEmpty();
        assertThat(empty.totalElements()).isZero();
        assertThat(empty.pageNumber()).isEqualTo(req.page());
        assertThat(empty.pageSize()).isEqualTo(req.size());
    }

    // -------------------------------------------------------------------------
    // PageRequest
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("PageRequest.of stores page and size")
    void pageRequestOf() {
        var req = PageRequest.of(3, 25);

        assertThat(req.page()).isEqualTo(3);
        assertThat(req.size()).isEqualTo(25);
    }

    @Test
    @DisplayName("PageRequest.first(n) creates page 0 with given size")
    void pageRequestFirst() {
        var req = PageRequest.first(50);

        assertThat(req.page()).isZero();
        assertThat(req.size()).isEqualTo(50);
    }

    @Test
    @DisplayName("PageRequest.offset returns page * size")
    void pageRequestOffset() {
        assertThat(PageRequest.of(0, 10).offset()).isZero();
        assertThat(PageRequest.of(1, 10).offset()).isEqualTo(10L);
        assertThat(PageRequest.of(3, 20).offset()).isEqualTo(60L);
    }

    @Test
    @DisplayName("PageRequest.of with negative size throws IllegalArgumentException")
    void pageRequestNegativeSizeThrows() {
        assertThatThrownBy(() -> PageRequest.of(0, -1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("PageRequest.of with negative page throws IllegalArgumentException")
    void pageRequestNegativePageThrows() {
        assertThatThrownBy(() -> PageRequest.of(-1, 10))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
