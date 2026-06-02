package dev.agenor.core;

/**
 * Pagination parameters for directory queries.
 *
 * @param page zero-based page index (must be ≥ 0)
 * @param size maximum number of elements per page (must be ≥ 1)
 * @since 0.20.0
 * @see Page
 */
public record PageRequest(int page, int size) {

    /**
     * Canonical constructor — validates parameters.
     */
    public PageRequest {
        if (page < 0) throw new IllegalArgumentException("page must be >= 0, got: " + page);
        if (size < 1) throw new IllegalArgumentException("size must be >= 1, got: " + size);
    }

    /**
     * Creates a page request for the given zero-based page index and page size.
     *
     * @param page zero-based page index
     * @param size maximum elements per page
     * @return a new page request
     */
    public static PageRequest of(int page, int size) {
        return new PageRequest(page, size);
    }

    /**
     * Creates a request for the first page with the given size.
     *
     * @param size maximum elements per page
     * @return a page request for page 0
     */
    public static PageRequest first(int size) {
        return new PageRequest(0, size);
    }

    /**
     * Returns a page request for the next page with the same size.
     */
    public PageRequest next() {
        return new PageRequest(page + 1, size);
    }

    /** Offset in elements (page × size). */
    public long offset() {
        return (long) page * size;
    }
}
