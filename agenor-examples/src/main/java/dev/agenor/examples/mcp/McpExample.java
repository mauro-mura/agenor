package dev.agenor.examples.mcp;

import dev.agenor.adapters.llm.LLMProviderFactory;
import dev.agenor.adapters.llm.openai.OpenAIProvider;
import dev.agenor.adapters.mcp.McpClientFactory;
import dev.agenor.adapters.mcp.McpFunctionAdapter;
import dev.agenor.adapters.mcp.McpToolRegistry;
import dev.agenor.core.llm.FunctionCall;
import dev.agenor.core.llm.LLMMessage;
import dev.agenor.core.llm.LLMRequest;
import dev.agenor.core.llm.LLMResponse;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * MCP Adapter example — connects to a filesystem MCP server via Docker.
 *
 * <p>Requires Docker installed and running. The MCP server runs in a container:
 * no local Node.js installation needed.
 *
 * <p><b>Run (tool listing + direct call only):</b>
 * <pre>
 *   mvn exec:java -pl agenor-examples \
 *       -Dexec.mainClass=dev.agenor.examples.McpExample
 * </pre>
 *
 * <p><b>Run (with LLM round-trip):</b>
 * <pre>
 *   ANTHROPIC_API_KEY=sk-ant-... mvn exec:java -pl agenor-examples \
 *       -Dexec.mainClass=dev.agenor.examples.McpExample
 * </pre>
 *
 * <p>The example mounts {@code /tmp} into the container. Override with:
 * <pre>
 *   -Dmcp.root=/path/to/dir
 * </pre>
 */
public class McpExample {

    private static final String MODEL   = OpenAIProvider.Models.GPT_4_1_MINI.toString();
    private static final String ROOT    = System.getProperty("mcp.root", "/tmp");
    private static final String IMAGE   = "node:22-alpine";
    private static final String PACKAGE = "@modelcontextprotocol/server-filesystem";

    public static void main(String[] args) throws Exception {
        System.out.println("=== Agenor MCP Adapter Example ===");
        System.out.println("Root: " + ROOT);
        System.out.println("Transport: Docker STDIO (" + IMAGE + " / " + PACKAGE + ")\n");

        checkDockerAvailable();

        // Start the MCP filesystem server in Docker via STDIO (no local Node.js required)
        var mcpClient = McpClientFactory.fromStdio(
                "docker", "run", "--rm", "-i",
                "-v", ROOT + ":" + ROOT,
                IMAGE,
                "npx", "-y", PACKAGE, ROOT
        );

        var registry = new McpToolRegistry(mcpClient);
        var adapter  = new McpFunctionAdapter(registry);

        try {
            // 1 — list tools
            System.out.println("--- Available tools ---");
            registry.getTools().get()
                    .forEach(t -> System.out.printf("  %-25s %s%n", t.name(), t.description()));

            // 2 — direct tool call (no LLM)
            System.out.println("\n--- list_directory(" + ROOT + ") ---");
            var result = registry.callTool("list_directory", Map.of("path", ROOT)).get();
            if (result.isError()) {
                System.out.println("  Error: " + result.content());
            } else {
                System.out.println(result.content());
            }

            // 3 — optional LLM round-trip
            String apiKey = System.getenv("OPENAI_API_KEY");
            if (apiKey != null && !apiKey.isBlank()) {
                llmRoundTrip(adapter, apiKey);
            } else {
                System.out.println("\n(Set OPENAI_API_KEY to enable the LLM round-trip demo)");
            }

        } finally {
            mcpClient.close();
        }
    }

    // -------------------------------------------------------------------------
    // LLM round-trip
    // -------------------------------------------------------------------------

    private static void llmRoundTrip(McpFunctionAdapter adapter, String apiKey) throws Exception {
        System.out.println("\n--- LLM round-trip (" + MODEL + ") ---");

        var provider  = LLMProviderFactory.openai()
                .apiKey(apiKey)
                .modelName(MODEL)
                .maxTokens(512)
                .build();

        var functions = adapter.buildFunctionDefinitions().get();

        List<LLMMessage> history = new ArrayList<>();
        history.add(LLMMessage.system(
                "You have filesystem access via tools. Use them to answer the user."));
        history.add(LLMMessage.user("List the contents of " + ROOT + "."));

        LLMRequest.Builder requestBuilder = LLMRequest.builder().model(MODEL)
                .maxTokens(512);
        functions.forEach(requestBuilder::addFunction);

        LLMResponse response = provider.chat(
                requestBuilder.messages(history).build()).get();

        // Tool-call loop
        while (response.hasFunctionCalls()) {
            history.add(LLMMessage.assistant(response.content(), response.functionCalls()));

            for (FunctionCall call : response.functionCalls()) {
                System.out.printf("  → calling %s%n", call.name());
                LLMMessage toolResult = adapter.execute(call).get();
                history.add(toolResult);
            }

            LLMRequest.Builder nextBuilder = LLMRequest.builder().model(MODEL).maxTokens(512);
            functions.forEach(nextBuilder::addFunction);
            response = provider.chat(nextBuilder.messages(history).build()).get();
        }

        System.out.println("\nLLM response:");
        System.out.println(response.content());
    }

    // -------------------------------------------------------------------------
    // Preflight check
    // -------------------------------------------------------------------------

    private static void checkDockerAvailable() throws Exception {
        try {
            int exit = new ProcessBuilder("docker", "info")
                    .redirectErrorStream(true)
                    .start()
                    .waitFor();
            if (exit != 0) {
                abort("Docker is not running. Please start Docker and retry.");
            }
        } catch (Exception e) {
            abort("Docker not found on PATH. Install Docker: https://docs.docker.com/get-docker/");
        }
    }

    private static void abort(String message) {
        System.err.println("ERROR: " + message);
        System.exit(1);
    }
}
