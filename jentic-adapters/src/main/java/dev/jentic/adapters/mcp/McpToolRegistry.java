package dev.jentic.adapters.mcp;

import dev.jentic.core.mcp.McpTool;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Thread-safe cache for MCP tool listings.
 *
 * <p>Lazy initialization: {@link #getTools()} triggers the first {@code listTools()} call
 * on first access. Subsequent calls return the cached result until the TTL expires.
 *
 * <p>Cache invalidation:
 * <ul>
 *   <li>Automatic: after {@code cacheTtl} elapses since the last fetch</li>
 *   <li>Immediate: call {@link #invalidate()} on {@code ToolListChanged} SDK notification</li>
 * </ul>
 *
 * <p>Default TTL is 60 seconds, configurable via constructor.
 */
public class McpToolRegistry {

    static final Duration DEFAULT_TTL = Duration.ofSeconds(60);

    private final McpClient client;
    private final Duration cacheTtl;
    private final Clock clock;

    private final AtomicReference<CacheEntry> cacheRef = new AtomicReference<>();

    public McpToolRegistry(McpClient client) {
        this(client, DEFAULT_TTL, Clock.systemUTC());
    }

    public McpToolRegistry(McpClient client, Duration cacheTtl) {
        this(client, cacheTtl, Clock.systemUTC());
    }

    /** Package-private constructor for testing with a controllable clock. */
    McpToolRegistry(McpClient client, Duration cacheTtl, Clock clock) {
        if (client == null) throw new IllegalArgumentException("client must not be null");
        if (cacheTtl == null || cacheTtl.isNegative() || cacheTtl.isZero()) {
            throw new IllegalArgumentException("cacheTtl must be positive");
        }
        this.client = client;
        this.cacheTtl = cacheTtl;
        this.clock = clock;
    }

    /**
     * Returns the cached tool list, fetching from the MCP server if the cache is
     * absent or expired.
     *
     * @return a future that completes with the (possibly cached) tool list
     */
    public CompletableFuture<List<McpTool>> getTools() {
        CacheEntry current = cacheRef.get();
        if (current != null && !current.isExpired(clock.instant(), cacheTtl)) {
            return CompletableFuture.completedFuture(current.tools);
        }
        return refresh();
    }

    /**
     * Calls a tool by name, delegating to the underlying {@link McpClient}.
     *
     * <p>This method does not use the cache — tool calls are always forwarded.
     */
    public CompletableFuture<dev.jentic.core.mcp.McpToolResult> callTool(
            String name, java.util.Map<String, Object> args) {
        return client.callTool(name, args);
    }

    /**
     * Immediately invalidates the cache.
     *
     * <p>Call this when an MCP {@code ToolListChanged} notification is received
     * from the SDK so the next {@link #getTools()} triggers a fresh fetch.
     */
    public void invalidate() {
        cacheRef.set(null);
    }

    // --- internals ---

    private CompletableFuture<List<McpTool>> refresh() {
        return client.listTools().thenApply(tools -> {
            cacheRef.set(new CacheEntry(tools, clock.instant()));
            return tools;
        });
    }

    private record CacheEntry(List<McpTool> tools, Instant fetchedAt) {
        boolean isExpired(Instant now, Duration ttl) {
            return now.isAfter(fetchedAt.plus(ttl));
        }
    }
}