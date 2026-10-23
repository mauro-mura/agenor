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

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link McpFunctionAdapter} against the reference MCP server,
 * {@code @modelcontextprotocol/server-everything} 2.0.0 on STDIO, started with {@code npx}.
 *
 * <p>Its tools are named with hyphens ({@code get-sum}, {@code get-tiny-image}, ...), which
 * {@code FunctionDefinition} used to reject: one such name failed the whole list, and the LLM
 * received no tool at all. Found by agenor-research.
 *
 * <p>STDIO rather than a container over SSE: the current MCP specification (2026-07-28) defines
 * only stdio and Streamable HTTP, and the HTTP+SSE transport, deprecated since 2025-03-26, is gone
 * from it. The defect does not depend on the transport anyway: it is in the conversion of the list.
 *
 * <p>Needs {@code npx} on the PATH and the npm registry reachable; {@code integration.yml} sets up
 * Node for it. Enable via:
 * {@code mvn verify -Dintegration.tests.enabled=true -pl agenor-adapters}
 */
@EnabledIfSystemProperty(named = "integration.tests.enabled", matches = "true")
@DisplayName("McpFunctionAdapter — against server-everything 2.0.0 on STDIO")
class McpServerEverythingIT {

    private static McpClient client;
    private static McpFunctionAdapter adapter;

    @BeforeAll
    static void start() {
        client = McpClientFactory.fromStdio("npx", "-y", "@modelcontextprotocol/server-everything@2.0.0", "stdio");
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
}
