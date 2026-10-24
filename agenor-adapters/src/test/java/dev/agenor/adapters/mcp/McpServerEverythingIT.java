package dev.agenor.adapters.mcp;

import dev.agenor.core.llm.FunctionCall;
import dev.agenor.core.llm.FunctionDefinition;
import dev.agenor.core.llm.LLMMessage;
import dev.agenor.core.mcp.McpClient;
import dev.agenor.core.mcp.McpTool;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link McpFunctionAdapter} against the reference MCP server,
 * {@code @modelcontextprotocol/server-everything} 2.0.0 on STDIO, started with {@code npx} inside
 * a {@code node} container.
 *
 * <p>Its tools are named with hyphens ({@code get-sum}, {@code get-tiny-image}, ...), which
 * {@code FunctionDefinition} used to reject: one such name failed the whole list, and the LLM
 * received no tool at all. Found by agenor-research.
 *
 * <p>STDIO rather than a container over SSE: the current MCP specification (2026-07-28) defines
 * only stdio and Streamable HTTP, and the HTTP+SSE transport, deprecated since 2025-03-26, is gone
 * from it. The defect does not depend on the transport anyway: it is in the conversion of the list.
 *
 * <p>Needs Docker, like the other ITs, and the npm registry reachable from a container; not Node on
 * the host. {@code docker run -i} keeps the transport STDIO: the client talks to the container's
 * stdin and stdout exactly as it would to a local {@code npx}. Enable via:
 * {@code mvn verify -Dintegration.tests.enabled=true -pl agenor-adapters}
 */
@EnabledIfSystemProperty(named = "integration.tests.enabled", matches = "true")
@DisplayName("McpFunctionAdapter — against server-everything 2.0.0 on STDIO")
class McpServerEverythingIT {

    private static final String NODE_IMAGE = "node:24.22.1-alpine";

    private static McpClient client;
    private static McpFunctionAdapter adapter;

    @BeforeAll
    static void start() throws Exception {
        // Pulled first because the client gives `initialize` 20 seconds, and a first pull of the
        // image can take longer than that on its own.
        var pull = new ProcessBuilder("docker", "pull", "-q", NODE_IMAGE).inheritIO().start();
        assertThat(pull.waitFor(5, TimeUnit.MINUTES) && pull.exitValue() == 0)
                .as("docker pull %s", NODE_IMAGE).isTrue();

        // --init so the container stops when the client closes its stdin
        client = McpClientFactory.fromStdio("docker", "run", "-i", "--rm", "--init", NODE_IMAGE,
                "npx", "-y", "@modelcontextprotocol/server-everything@2.0.0", "stdio");
        adapter = new McpFunctionAdapter(new McpToolRegistry(client));
    }

    @AfterAll
    static void stop() throws Exception {
        if (client != null) {
            client.close();
        }
    }

    @Test
    void everyToolReachesTheLlm() throws Exception {
        List<McpTool> tools = client.listTools().get(30, TimeUnit.SECONDS);

        List<FunctionDefinition> definitions = adapter.buildFunctionDefinitions().get(30, TimeUnit.SECONDS);

        assertThat(tools).hasSizeGreaterThanOrEqualTo(16);
        assertThat(definitions).extracting(FunctionDefinition::name)
                .containsExactlyInAnyOrderElementsOf(tools.stream().map(McpTool::name).toList())
                .contains("get-sum");
    }

    /** The hyphenated name the LLM calls is the one the server knows. */
    @Test
    void aHyphenatedToolCanBeCalled() throws Exception {
        LLMMessage result = adapter.execute(FunctionCall.of("get-sum", "{\"a\": 2, \"b\": 3}"))
                .get(30, TimeUnit.SECONDS);

        assertThat(result.content()).contains("5");
    }

    /** The control: well-formed arguments reach the tool, which honours them. */
    @Test
    void wellFormedArgumentsReachTheTool() throws Exception {
        LLMMessage result = adapter.execute(FunctionCall.of("get-resource-links", "{\"count\": 1}"))
                .get(30, TimeUnit.SECONDS);

        assertThat(result.content()).startsWith("Here are 1 resource links");
    }

    /**
     * Malformed arguments used to become {}, and the tool ran with its default count of 3 (D3.4).
     * Now the tool is not called, and the model reads why.
     */
    @ParameterizedTest
    @ValueSource(strings = {"{\"count\": 1", "count=1", "[1]"})
    void malformedArgumentsDoNotReachTheTool(String arguments) throws Exception {
        LLMMessage result = adapter.execute(FunctionCall.of("get-resource-links", arguments))
                .get(30, TimeUnit.SECONDS);

        assertThat(result.content())
                .doesNotContain("resource links")
                .contains("not a JSON object", arguments);
    }
}
