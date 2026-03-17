package dev.jentic.adapters.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.jentic.core.llm.FunctionCall;
import dev.jentic.core.llm.FunctionDefinition;
import dev.jentic.core.llm.LLMMessage;
import dev.jentic.core.mcp.McpTool;
import dev.jentic.core.mcp.McpToolResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class McpFunctionBridgeTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Mock
    private McpToolRegistry registry;

    private McpFunctionBridge behavior;

    @BeforeEach
    void setUp() {
        behavior = new McpFunctionBridge(registry);
    }

    // --- buildFunctionDefinitions ---

    @Test
    void buildFunctionDefinitions_convertsToolsToFunctions() throws Exception {
        McpTool tool = new McpTool("read_file", "Reads a file from disk", null);
        when(registry.getTools()).thenReturn(CompletableFuture.completedFuture(List.of(tool)));

        List<FunctionDefinition> functions = behavior.buildFunctionDefinitions().get();

        assertThat(functions).hasSize(1);
        assertThat(functions.get(0).name()).isEqualTo("read_file");
        assertThat(functions.get(0).description()).isEqualTo("Reads a file from disk");
    }

    @Test
    void buildFunctionDefinitions_withInputSchema_includesParameters() throws Exception {
        JsonNode schema = MAPPER.readTree("""
                {"type":"object","properties":{"path":{"type":"string"}},"required":["path"]}
                """);
        McpTool tool = new McpTool("read_file", "Reads a file", schema);
        when(registry.getTools()).thenReturn(CompletableFuture.completedFuture(List.of(tool)));

        List<FunctionDefinition> functions = behavior.buildFunctionDefinitions().get();

        FunctionDefinition fn = functions.get(0);
        assertThat(fn.parameters()).isNotNull();
        assertThat(fn.getProperties()).containsKey("path");
    }

    @Test
    void buildFunctionDefinitions_emptyToolList_returnsEmptyList() throws Exception {
        when(registry.getTools()).thenReturn(CompletableFuture.completedFuture(List.of()));

        List<FunctionDefinition> functions = behavior.buildFunctionDefinitions().get();

        assertThat(functions).isEmpty();
    }

    @Test
    void buildFunctionDefinitions_multipleTools_allConverted() throws Exception {
        McpTool toolA = new McpTool("read_file", "Reads a file", null);
        McpTool toolB = new McpTool("list_dir", "Lists a directory", null);
        when(registry.getTools()).thenReturn(CompletableFuture.completedFuture(List.of(toolA, toolB)));

        List<FunctionDefinition> functions = behavior.buildFunctionDefinitions().get();

        assertThat(functions).extracting(FunctionDefinition::name)
                .containsExactly("read_file", "list_dir");
    }

    // --- execute ---

    @Test
    void execute_dispatchesCallToolWithParsedArgs() throws Exception {
        FunctionCall call = FunctionCall.of("read_file", "{\"path\":\"/tmp/test.txt\"}");
        when(registry.callTool(eq("read_file"), eq(Map.of("path", "/tmp/test.txt"))))
                .thenReturn(CompletableFuture.completedFuture(McpToolResult.success("file contents")));

        LLMMessage result = behavior.execute(call).get();

        assertThat(result.isFunction()).isTrue();
        assertThat(result.name()).isEqualTo("read_file");
        assertThat(result.content()).isEqualTo("file contents");
        verify(registry).callTool("read_file", Map.of("path", "/tmp/test.txt"));
    }

    @Test
    void execute_toolReturnsError_functionMessageContainsErrorContent() throws Exception {
        FunctionCall call = FunctionCall.of("read_file", "{\"path\":\"/missing\"}");
        when(registry.callTool(any(), any()))
                .thenReturn(CompletableFuture.completedFuture(McpToolResult.error("file not found")));

        LLMMessage result = behavior.execute(call).get();

        assertThat(result.isFunction()).isTrue();
        assertThat(result.content()).isEqualTo("file not found");
    }

    @Test
    void execute_noArguments_callsToolWithEmptyMap() throws Exception {
        FunctionCall call = FunctionCall.of("ping");
        when(registry.callTool(eq("ping"), eq(Map.of())))
                .thenReturn(CompletableFuture.completedFuture(McpToolResult.success("pong")));

        LLMMessage result = behavior.execute(call).get();

        assertThat(result.content()).isEqualTo("pong");
        verify(registry).callTool("ping", Map.of());
    }

    // --- schemaToParameters (static helper) ---

    @Test
    void schemaToParameters_nullSchema_returnsNull() {
        assertThat(McpFunctionBridge.schemaToParameters(null)).isNull();
    }

    @Test
    void schemaToParameters_nonObjectNode_returnsNull() throws Exception {
        JsonNode arrayNode = MAPPER.readTree("[\"a\",\"b\"]");
        assertThat(McpFunctionBridge.schemaToParameters(arrayNode)).isNull();
    }

    @Test
    void schemaToParameters_objectNode_returnsMap() throws Exception {
        JsonNode schema = MAPPER.readTree("{\"type\":\"object\",\"properties\":{}}");
        Map<String, Object> result = McpFunctionBridge.schemaToParameters(schema);
        assertThat(result).containsEntry("type", "object");
    }

    // --- parseArguments (static helper) ---

    @Test
    void parseArguments_validJson_returnsParsedMap() {
        Map<String, Object> result = McpFunctionBridge.parseArguments("{\"path\":\"/tmp\",\"recursive\":true}");
        assertThat(result).containsEntry("path", "/tmp").containsEntry("recursive", true);
    }

    @Test
    void parseArguments_emptyJson_returnsEmptyMap() {
        assertThat(McpFunctionBridge.parseArguments("{}")).isEmpty();
    }

    @Test
    void parseArguments_nullArguments_returnsEmptyMap() {
        assertThat(McpFunctionBridge.parseArguments(null)).isEmpty();
    }

    @Test
    void parseArguments_blankArguments_returnsEmptyMap() {
        assertThat(McpFunctionBridge.parseArguments("   ")).isEmpty();
    }

    @Test
    void parseArguments_invalidJson_returnsEmptyMap() {
        assertThat(McpFunctionBridge.parseArguments("not-json")).isEmpty();
    }

    // --- constructor ---

    @Test
    void constructor_nullRegistry_throws() {
        assertThatThrownBy(() -> new McpFunctionBridge(null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}