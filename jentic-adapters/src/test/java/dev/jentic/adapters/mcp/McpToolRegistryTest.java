package dev.jentic.adapters.mcp;

import dev.jentic.core.mcp.McpClient;
import dev.jentic.core.mcp.McpTool;
import dev.jentic.core.mcp.McpToolResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class McpToolRegistryTest {

    private static final McpTool TOOL_A = new McpTool("tool_a", "Tool A", null);
    private static final McpTool TOOL_B = new McpTool("tool_b", "Tool B", null);

    private AtomicInteger listCallCount;
    private McpClient mockClient;

    @BeforeEach
    void setUp() {
        listCallCount = new AtomicInteger(0);
        mockClient = new McpClient() {
            @Override
            public CompletableFuture<List<McpTool>> listTools() {
                listCallCount.incrementAndGet();
                return CompletableFuture.completedFuture(List.of(TOOL_A, TOOL_B));
            }

            @Override
            public CompletableFuture<McpToolResult> callTool(String name, Map<String, Object> args) {
                return CompletableFuture.completedFuture(McpToolResult.success("ok"));
            }

            @Override
            public void close() {}
        };
    }

    @Test
    void firstAccess_shouldCallListTools() throws Exception {
        var registry = new McpToolRegistry(mockClient);

        List<McpTool> tools = registry.getTools().get();

        assertEquals(1, listCallCount.get());
        assertEquals(2, tools.size());
    }

    @Test
    void secondAccessWithinTtl_shouldReturnCachedResult() throws Exception {
        var registry = new McpToolRegistry(mockClient, Duration.ofSeconds(60));

        registry.getTools().get();
        registry.getTools().get();

        assertEquals(1, listCallCount.get(), "listTools() must not be called twice within TTL");
    }

    @Test
    void accessAfterTtlExpiry_shouldRefetch() throws Exception {
        var registry = new McpToolRegistry(mockClient, Duration.ofNanos(1));

        registry.getTools().get();
        Thread.sleep(5); // ensure TTL expires
        registry.getTools().get();

        assertEquals(2, listCallCount.get(), "listTools() must be called again after TTL expiry");
    }

    @Test
    void invalidate_shouldTriggerRefetchOnNextAccess() throws Exception {
        var registry = new McpToolRegistry(mockClient, Duration.ofSeconds(60));

        registry.getTools().get();
        registry.invalidate();
        registry.getTools().get();

        assertEquals(2, listCallCount.get(), "listTools() must be called again after invalidation");
    }

    @Test
    void invalidate_shouldClearCacheImmediately() throws Exception {
        var registry = new McpToolRegistry(mockClient, Duration.ofSeconds(60));

        registry.getTools().get();
        assertEquals(1, listCallCount.get());

        registry.invalidate();
        // After invalidation, a new fetch must happen even within TTL
        registry.getTools().get();
        assertEquals(2, listCallCount.get());
    }

    @Test
    void callTool_shouldDelegateDirectlyToClient() throws Exception {
        var registry = new McpToolRegistry(mockClient);

        McpToolResult result = registry.callTool("tool_a", Map.of("key", "value")).get();

        assertFalse(result.isError());
        assertEquals("ok", result.content());
    }

    @Test
    void constructor_shouldRejectNullClient() {
        assertThrows(IllegalArgumentException.class, () -> new McpToolRegistry(null));
    }

    @Test
    void constructor_shouldRejectZeroTtl() {
        assertThrows(IllegalArgumentException.class,
                () -> new McpToolRegistry(mockClient, Duration.ZERO));
    }

    @Test
    void constructor_shouldRejectNegativeTtl() {
        assertThrows(IllegalArgumentException.class,
                () -> new McpToolRegistry(mockClient, Duration.ofSeconds(-1)));
    }
}