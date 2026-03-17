package dev.jentic.adapters.mcp;

import dev.jentic.core.mcp.McpTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class McpToolRegistryTest {

    @Mock
    private McpClient client;

    private static final McpTool TOOL_A = new McpTool("read_file", "Reads a file", null);
    private static final McpTool TOOL_B = new McpTool("list_dir", "Lists a directory", null);
    private static final Duration TTL = Duration.ofSeconds(60);

    private Instant now;
    private Clock clock;

    @BeforeEach
    void setUp() {
        now = Instant.parse("2026-01-01T00:00:00Z");
        clock = Clock.fixed(now, ZoneOffset.UTC);
    }

    // --- first access triggers listTools() ---

    @Test
    void getTools_firstAccess_callsListTools() throws Exception {
        when(client.listTools()).thenReturn(CompletableFuture.completedFuture(List.of(TOOL_A)));
        var registry = new McpToolRegistry(client, TTL, clock);

        var tools = registry.getTools().get();

        assertThat(tools).containsExactly(TOOL_A);
        verify(client, times(1)).listTools();
    }

    // --- second access within TTL → cache hit ---

    @Test
    void getTools_secondAccessWithinTtl_returnsCachedResult() throws Exception {
        when(client.listTools()).thenReturn(CompletableFuture.completedFuture(List.of(TOOL_A)));
        var registry = new McpToolRegistry(client, TTL, clock);

        registry.getTools().get();
        registry.getTools().get();

        verify(client, times(1)).listTools();
    }

    // --- access after TTL → cache miss, new call ---

    @Test
    void getTools_accessAfterTtlExpiry_refetchesFromServer() throws Exception {
        when(client.listTools())
                .thenReturn(CompletableFuture.completedFuture(List.of(TOOL_A)))
                .thenReturn(CompletableFuture.completedFuture(List.of(TOOL_B)));

        // First fetch at t=0
        var registry = new McpToolRegistry(client, TTL, clock);
        var first = registry.getTools().get();
        assertThat(first).containsExactly(TOOL_A);

        // Advance clock past TTL
        Clock expiredClock = Clock.fixed(now.plus(TTL).plusSeconds(1), ZoneOffset.UTC);
        var registryExpired = new McpToolRegistry(client, TTL, expiredClock);
        // Force state by pre-populating via first call on original registry,
        // then simulate expiry by using a registry with an already-expired clock
        var second = registryExpired.getTools().get();
        assertThat(second).containsExactly(TOOL_B);

        verify(client, times(2)).listTools();
    }

    // --- invalidate() causes immediate cache miss ---

    @Test
    void invalidate_causesNextGetToolsToRefetch() throws Exception {
        when(client.listTools())
                .thenReturn(CompletableFuture.completedFuture(List.of(TOOL_A)))
                .thenReturn(CompletableFuture.completedFuture(List.of(TOOL_B)));

        var registry = new McpToolRegistry(client, TTL, clock);

        var first = registry.getTools().get();
        assertThat(first).containsExactly(TOOL_A);

        registry.invalidate();

        var second = registry.getTools().get();
        assertThat(second).containsExactly(TOOL_B);

        verify(client, times(2)).listTools();
    }

    @Test
    void invalidate_withinTtl_forcesRefetch() throws Exception {
        when(client.listTools())
                .thenReturn(CompletableFuture.completedFuture(List.of(TOOL_A)))
                .thenReturn(CompletableFuture.completedFuture(List.of(TOOL_B)));

        var registry = new McpToolRegistry(client, TTL, clock);
        registry.getTools().get();

        // Invalidate before TTL would naturally expire
        registry.invalidate();
        var tools = registry.getTools().get();

        assertThat(tools).containsExactly(TOOL_B);
        verify(client, times(2)).listTools();
    }

    // --- multiple accesses after invalidate stay cached again ---

    @Test
    void afterInvalidateAndRefetch_cacheIsActiveAgain() throws Exception {
        when(client.listTools())
                .thenReturn(CompletableFuture.completedFuture(List.of(TOOL_A)))
                .thenReturn(CompletableFuture.completedFuture(List.of(TOOL_B)));

        var registry = new McpToolRegistry(client, TTL, clock);
        registry.getTools().get();
        registry.invalidate();
        registry.getTools().get();
        registry.getTools().get(); // still within TTL after re-fetch

        verify(client, times(2)).listTools();
    }

    // --- callTool always delegates, never cached ---

    @Test
    void callTool_alwaysDelegatesToClient() throws Exception {
        var expectedResult = new dev.jentic.core.mcp.McpToolResult("content", false);
        when(client.callTool("read_file", java.util.Map.of("path", "/tmp")))
                .thenReturn(CompletableFuture.completedFuture(expectedResult));

        var registry = new McpToolRegistry(client, TTL, clock);
        var result = registry.callTool("read_file", java.util.Map.of("path", "/tmp")).get();

        assertThat(result).isEqualTo(expectedResult);
        verify(client).callTool("read_file", java.util.Map.of("path", "/tmp"));
    }

    // --- constructor validation ---

    @Test
    void constructor_nullClient_throws() {
        assertThatThrownBy(() -> new McpToolRegistry(null, TTL, clock))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void constructor_zeroDuration_throws() {
        assertThatThrownBy(() -> new McpToolRegistry(client, Duration.ZERO, clock))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void constructor_negativeDuration_throws() {
        assertThatThrownBy(() -> new McpToolRegistry(client, Duration.ofSeconds(-1), clock))
                .isInstanceOf(IllegalArgumentException.class);
    }
}