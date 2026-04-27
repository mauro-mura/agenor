package dev.jentic.core;

import java.util.List;

/**
 * A single page of results from a paginated query.
 *
 * <p>Used by {@link dev.jentic.core.directory.AgentDiscovery#findAgents} to return
 * bounded result sets from potentially large remote directories.
 *
 * @param <T>           element type
 * @param content       elements on this page (never null, may be empty)
 * @param totalElements total number of elements across all pages
 * @param pageNumber    zero-based page index
 * @param pageSize      maximum elements per page as requested
 * @since 0.20.0
 */
public record Page<T>(
        List<T> content,
        long totalElements,
        int pageNumber,
        int pageSize
) {

    /**
     * Compact constructor — defensively copies {@code content}.
     */
    public Page {
        content = content != null ? List.copyOf(content) : List.of();
    }

    /**
     * Returns {@code true} if this page contains no elements.
     */
    public boolean isEmpty() {
        return content.isEmpty();
    }

    /**
     * Returns the number of elements on this page.
     */
    public int numberOfElements() {
        return content.size();
    }

    /**
     * Returns {@code true} if there is a next page.
     */
    public boolean hasNext() {
        return (long) (pageNumber + 1) * pageSize < totalElements;
    }

    /**
     * Returns an empty page with the given {@link PageRequest} parameters.
     *
     * @param <T>  element type
     * @param page the page request that produced this empty result
     * @return an empty page
     */
    public static <T> Page<T> empty(PageRequest page) {
        return new Page<>(List.of(), 0L, page.page(), page.size());
    }
}
