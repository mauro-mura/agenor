package dev.agenor.adapters.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.agenor.core.llm.FunctionCall;
import dev.agenor.core.llm.FunctionDefinition;
import dev.agenor.core.llm.LLMMessage;
import dev.agenor.core.mcp.McpClient;
import dev.agenor.core.mcp.McpTool;
import dev.agenor.core.mcp.McpToolResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class McpFunctionAdapterTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private McpTool readFileTool;
    private McpTool noSchemaTool;
    private McpFunctionAdapter adapter;

    // Captures the last callTool invocation for assertion
    private AtomicReference<String> capturedToolName;
    private AtomicReference<Map<String, Object>> capturedArgs;

    @BeforeEach
    void setUp() throws Exception {
        capturedToolName = new AtomicReference<>();
        capturedArgs     = new AtomicReference<>();

        var schema = MAPPER.readTree("""
                {"type":"object","properties":{"path":{"type":"string"}},"required":["path"]}
                """);
        readFileTool = new McpTool("read_file", "Read file contents", schema);
        noSchemaTool = new McpTool("ping", "Ping the server", null);

        McpClient mockClient = new McpClient() {
            @Override
            public CompletableFuture<List<McpTool>> listTools() {
                return CompletableFuture.completedFuture(List.of(readFileTool, noSchemaTool));
            }

            @Override
            public CompletableFuture<McpToolResult> callTool(String name, Map<String, Object> args) {
                capturedToolName.set(name);
                capturedArgs.set(args);
                return CompletableFuture.completedFuture(
                        McpToolResult.success("file content here"));
            }

            @Override
            public void close() {}
        };

        McpToolRegistry registry = new McpToolRegistry(mockClient);
        adapter  = new McpFunctionAdapter(registry);
    }

    // -------------------------------------------------------------------------
    // buildFunctionDefinitions
    // -------------------------------------------------------------------------

    @Test
    void buildFunctionDefinitions_shouldReturnOneDefinitionPerTool() throws Exception {
        List<FunctionDefinition> defs = adapter.buildFunctionDefinitions().get();

        assertEquals(2, defs.size());
    }

    @Test
    void buildFunctionDefinitions_toolWithSchema_shouldPreserveName() throws Exception {
        List<FunctionDefinition> defs = adapter.buildFunctionDefinitions().get();

        FunctionDefinition def = findByName(defs, "read_file");
        assertNotNull(def);
        assertEquals("Read file contents", def.description());
    }

    @Test
    void buildFunctionDefinitions_toolWithSchema_shouldExposePathProperty() throws Exception {
        List<FunctionDefinition> defs = adapter.buildFunctionDefinitions().get();

        FunctionDefinition def = findByName(defs, "read_file");
        assertNotNull(def);
        assertTrue(def.getProperties().containsKey("path"),
                "Expected 'path' in function parameters");
    }

    @Test
    void buildFunctionDefinitions_toolWithNullSchema_shouldHaveNullParameters() throws Exception {
        List<FunctionDefinition> defs = adapter.buildFunctionDefinitions().get();

        FunctionDefinition def = findByName(defs, "ping");
        assertNotNull(def);
        assertNull(def.parameters(), "Tool with null schema should produce null parameters");
    }

    // -------------------------------------------------------------------------
    // execute
    // -------------------------------------------------------------------------

    @Test
    void execute_shouldInvokeCallToolWithCorrectName() throws Exception {
        FunctionCall call = new FunctionCall("call-1", "read_file",
                "{\"path\":\"/tmp/test.txt\"}");

        adapter.execute(call).get();

        assertEquals("read_file", capturedToolName.get());
    }

    @Test
    void execute_shouldParseArgumentsAndPassToRegistry() throws Exception {
        FunctionCall call = new FunctionCall("call-1", "read_file",
                "{\"path\":\"/tmp/test.txt\"}");

        adapter.execute(call).get();

        assertEquals("/tmp/test.txt", capturedArgs.get().get("path"));
    }

    @Test
    void execute_shouldReturnFunctionMessageWithToolResult() throws Exception {
        FunctionCall call = new FunctionCall("call-1", "read_file",
                "{\"path\":\"/tmp/test.txt\"}");

        LLMMessage result = adapter.execute(call).get();

        assertTrue(result.isFunction());
        assertEquals("file content here", result.content());
        assertEquals("read_file", result.name());
    }

    @Test
    void execute_withEmptyArguments_shouldPassEmptyMap() throws Exception {
        FunctionCall call = new FunctionCall("call-1", "ping", "{}");

        adapter.execute(call).get();

        assertNotNull(capturedArgs.get());
        assertTrue(capturedArgs.get().isEmpty());
    }

    @Test
    void execute_withNullArguments_shouldPassEmptyMap() throws Exception {
        FunctionCall call = new FunctionCall("call-1", "ping", null);

        adapter.execute(call).get();

        assertNotNull(capturedArgs.get());
        assertTrue(capturedArgs.get().isEmpty());
    }

    // -------------------------------------------------------------------------
    // constructor
    // -------------------------------------------------------------------------

    @Test
    void constructor_shouldRejectNullRegistry() {
        assertThrows(IllegalArgumentException.class, () -> new McpFunctionAdapter(null));
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static FunctionDefinition findByName(List<FunctionDefinition> defs, String name) {
        return defs.stream()
                .filter(d -> name.equals(d.name()))
                .findFirst()
                .orElse(null);
    }
}
