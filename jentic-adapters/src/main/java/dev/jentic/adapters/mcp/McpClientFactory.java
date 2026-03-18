package dev.jentic.adapters.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;

/**
 * Factory for {@link JenticMcpClientAdapter}.
 *
 * <p>Supported transports (F2 scope):
 * <ul>
 *   <li><b>SSE</b>   — {@link #fromSse(String)} / {@link #fromSse(String, Executor)}</li>
 *   <li><b>STDIO</b> — {@link #fromStdio(String, String...)} / {@link #fromStdio(Executor, String, String...)}</li>
 * </ul>
 *
 * <p>Every factory method performs the MCP {@code initialize()} handshake synchronously
 * before returning. Callers should invoke these methods outside of hot paths.
 *
 * <p>Note: {@code io.modelcontextprotocol.client.McpClient} is the SDK builder entry point,
 * distinct from {@code dev.jentic.core.mcp.McpClient} (Jentic interface).
 */
public final class McpClientFactory {

    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);
    private static final JacksonMcpJsonMapper DEFAULT_JSON_MAPPER =
            new JacksonMcpJsonMapper(new ObjectMapper());

    private McpClientFactory() {
        throw new UnsupportedOperationException("utility class");
    }

    // -------------------------------------------------------------------------
    // SSE transport
    // -------------------------------------------------------------------------

    /**
     * Connects to an MCP server via SSE using {@code ForkJoinPool.commonPool()}.
     *
     * @param serverUrl base URL of the MCP server (e.g. {@code http://localhost:3000/sse})
     */
    public static JenticMcpClientAdapter fromSse(String serverUrl) {
        return fromSse(serverUrl, ForkJoinPool.commonPool());
    }

    /**
     * Connects to an MCP server via SSE using a custom executor.
     *
     * @param serverUrl base URL of the MCP server
     * @param executor  executor used for {@code supplyAsync()} calls
     */
    public static JenticMcpClientAdapter fromSse(String serverUrl, Executor executor) {
        var transport = HttpClientSseClientTransport.builder(serverUrl)
                .jsonMapper(DEFAULT_JSON_MAPPER)
                .build();
        var sdkClient = McpClient.sync(transport)
                .requestTimeout(DEFAULT_TIMEOUT)
                .build();
        sdkClient.initialize();
        return new JenticMcpClientAdapter(sdkClient, executor);
    }

    // -------------------------------------------------------------------------
    // STDIO transport (external subprocess)
    // -------------------------------------------------------------------------

    /**
     * Launches a local MCP server subprocess and connects via STDIO.
     *
     * <pre>
     *   McpClientFactory.fromStdio("npx", "-y", "@modelcontextprotocol/server-filesystem", "/tmp")
     * </pre>
     *
     * @param command first element of the command line (e.g. {@code "npx"})
     * @param args    remaining arguments
     */
    public static JenticMcpClientAdapter fromStdio(String command, String... args) {
        return fromStdio(ForkJoinPool.commonPool(), command, args);
    }

    /**
     * Launches a local MCP server subprocess and connects via STDIO using a custom executor.
     *
     * @param executor executor used for {@code supplyAsync()} calls
     * @param command  first element of the command line
     * @param args     remaining arguments
     */
    public static JenticMcpClientAdapter fromStdio(Executor executor, String command, String... args) {
        var fullCommand = new ArrayList<String>();
        fullCommand.add(command);
        fullCommand.addAll(List.of(args));

        var params = ServerParameters.builder(fullCommand.getFirst())
                .args(fullCommand.subList(1, fullCommand.size()))
                .build();
        var transport = new StdioClientTransport(params, DEFAULT_JSON_MAPPER);
        var sdkClient = McpClient.sync(transport)
                .requestTimeout(DEFAULT_TIMEOUT)
                .build();
        sdkClient.initialize();
        return new JenticMcpClientAdapter(sdkClient, executor);
    }
}