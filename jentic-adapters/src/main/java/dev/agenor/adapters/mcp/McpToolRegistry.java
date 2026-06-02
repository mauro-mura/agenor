package dev.agenor.adapters.mcp;

import dev.agenor.core.mcp.McpClient;
import dev.agenor.core.mcp.McpTool;
import dev.agenor.core.mcp.McpToolResult;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Thread-safe cache layer over {@link McpClient}.
 *
 * <p>Tool list is fetched lazily on first access and cached for {@code ttl} (default 60s).
 * The cache is invalidated immediately when {@link #invalidate()} is called — intended to be
 * wired to the SDK's {@code ToolListChanged} notification.
 *
 * <p>Usage:
 * <pre>
 *   McpToolRegistry registry = new McpToolRegistry(mcpClient);
 *   registry.getTools().thenAccept(tools -> ...);
 *   registry.callTool("read_file", Map.of("path", "/tmp/foo.txt")).thenAccept(result -> ...);
 * </pre>
 */
public class McpToolRegistry {

    static final Duration DEFAULT_TTL = Duration.ofSeconds(60);

    private final McpClient client;
    private final Duration ttl;

    /** Cached snapshot; {@code null} means cache is empty (never fetched or invalidated). */
    private final AtomicReference<CachedSnapshot> cache = new AtomicReference<>(null);

    public McpToolRegistry(McpClient client) {
        this(client, DEFAULT_TTL);
    }

    public McpToolRegistry(McpClient client, Duration ttl) {
        if (client == null) throw new IllegalArgumentException("client must not be null");
        if (ttl == null || ttl.isNegative() || ttl.isZero())
            throw new IllegalArgumentException("ttl must be positive");
        this.client = client;
        this.ttl    = ttl;
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Returns the cached tool list, fetching from the server if the cache is
     * empty or expired.
     */
    public CompletableFuture<List<McpTool>> getTools() {
        CachedSnapshot snapshot = cache.get();
        if (snapshot != null && !snapshot.isExpired(ttl)) {
            return CompletableFuture.completedFuture(snapshot.tools());
        }
        return refresh();
    }

    /**
     * Invokes a tool on the underlying {@link McpClient} directly (no caching).
     *
     * @param name tool name as returned by {@link #getTools()}
     * @param args arguments conforming to the tool's input schema
     */
    public CompletableFuture<McpToolResult> callTool(String name, Map<String, Object> args) {
        return client.callTool(name, args);
    }

    /**
     * Invalidates the cached tool list immediately.
     * The next call to {@link #getTools()} will trigger a fresh fetch.
     *
     * <p>Wire this to the SDK's {@code ToolListChanged} notification:
     * <pre>
     *   sdkClient.addRootNotificationHandler(n -> {
     *       if (n instanceof McpSchema.ToolListChangedNotification) registry.invalidate();
     *   });
     * </pre>
     */
    public void invalidate() {
        cache.set(null);
    }

    // -------------------------------------------------------------------------
    // Internal
    // -------------------------------------------------------------------------

    private CompletableFuture<List<McpTool>> refresh() {
        return client.listTools().thenApply(tools -> {
            cache.set(new CachedSnapshot(tools, Instant.now()));
            return tools;
        });
    }

    // -------------------------------------------------------------------------
    // Snapshot record
    // -------------------------------------------------------------------------

    private record CachedSnapshot(List<McpTool> tools, Instant fetchedAt) {
        boolean isExpired(Duration ttl) {
            return Instant.now().isAfter(fetchedAt.plus(ttl));
        }
    }
}
