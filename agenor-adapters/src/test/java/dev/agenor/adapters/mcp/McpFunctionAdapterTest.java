package dev.agenor.adapters.mcp;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.TextNode;
import dev.agenor.core.llm.FunctionCall;
import dev.agenor.core.llm.FunctionDefinition;
import dev.agenor.core.llm.LLMMessage;
import dev.agenor.core.mcp.McpClient;
import dev.agenor.core.mcp.McpTool;
import dev.agenor.core.mcp.McpToolResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;

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
        // Paired to the call it answers, not just named after it.
        assertEquals("call-1", result.functionCallId());
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
    // Malformed arguments and schemas
    // -------------------------------------------------------------------------

    /**
     * Arguments that are not one JSON object used to become {}, and the tool ran with its
     * defaults (D3.4: 3 links instead of the 1 asked for). Now the tool is not called, and the
     * model is told why.
     */
    @ParameterizedTest
    @ValueSource(strings = {"{\"count\": 1", "count=1", "[1]", "null", "{\"count\": 1} trailing"})
    void execute_argumentsThatAreNotAJsonObject_shouldNotCallTheTool(String arguments) throws Exception {
        List<ILoggingEvent> warnings = warningsDuring(() -> {
            LLMMessage result = adapter.execute(FunctionCall.of("read_file", arguments)).join();

            assertEquals(LLMMessage.Role.FUNCTION, result.role());
            assertTrue(result.content().contains("not a JSON object"), result.content());
            assertTrue(result.content().contains(arguments), result.content());
        });

        assertNull(capturedToolName.get(), "the tool must not be called");
        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).getFormattedMessage().contains("'read_file'"));
    }

    @Test
    void execute_longMalformedArguments_shouldBeShortenedInTheMessage() throws Exception {
        String arguments = "x".repeat(1000);

        LLMMessage result = adapter.execute(FunctionCall.of("read_file", arguments)).get();

        assertTrue(result.content().length() < 400, "length " + result.content().length());
        assertNull(capturedToolName.get());
    }

    /** A schema that is not an object used to make a tool with no parameters; now it is left out. */
    @Test
    void buildFunctionDefinitions_schemaThatIsNotAnObject_shouldBeLeftOutWithAWarn() throws Exception {
        var broken = new McpTool("broken", "Declares a schema that is a string", TextNode.valueOf("object"));
        var defs = new AtomicReference<List<FunctionDefinition>>();

        List<ILoggingEvent> warnings = warningsDuring(() ->
                defs.set(adapterFor(readFileTool, broken).buildFunctionDefinitions().join()));

        assertEquals(List.of("read_file"), defs.get().stream().map(FunctionDefinition::name).toList());
        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).getFormattedMessage().contains("'broken'"),
                warnings.get(0).getFormattedMessage());
    }

    private static List<ILoggingEvent> warningsDuring(Runnable action) {
        var logger = (Logger) LoggerFactory.getLogger(McpFunctionAdapter.class);
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            action.run();
        } finally {
            logger.detachAppender(appender);
        }
        return appender.list.stream().filter(e -> e.getLevel() == Level.WARN).toList();
    }

    // -------------------------------------------------------------------------
    // Tool names
    // -------------------------------------------------------------------------

    /** A hyphen is what most MCP servers use, and every provider accepts it. */
    @Test
    void buildFunctionDefinitions_hyphenatedName_shouldReachTheLlm() throws Exception {
        var defs = adapterFor(new McpTool("get-sum", "Adds two numbers", null)).buildFunctionDefinitions().get();

        assertEquals(List.of("get-sum"), defs.stream().map(FunctionDefinition::name).toList());
    }

    /** One name no provider accepts used to fail the whole list, leaving the LLM with no tool at all. */
    @Test
    void buildFunctionDefinitions_nameNoProviderAccepts_shouldBeLeftOutWithAWarn() throws Exception {
        var logger = (Logger) LoggerFactory.getLogger(McpFunctionAdapter.class);
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        List<FunctionDefinition> defs;
        try {
            defs = adapterFor(new McpTool("get-sum", "Adds two numbers", null),
                    new McpTool("files.read", "Reads a file", null)).buildFunctionDefinitions().get();
        } finally {
            logger.detachAppender(appender);
        }

        assertEquals(List.of("get-sum"), defs.stream().map(FunctionDefinition::name).toList());
        List<ILoggingEvent> warnings = appender.list.stream().filter(e -> e.getLevel() == Level.WARN).toList();
        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).getFormattedMessage().contains("'files.read'"),
                warnings.get(0).getFormattedMessage());
    }

    private static McpFunctionAdapter adapterFor(McpTool... tools) {
        McpClient client = new McpClient() {
            @Override
            public CompletableFuture<List<McpTool>> listTools() {
                return CompletableFuture.completedFuture(List.of(tools));
            }

            @Override
            public CompletableFuture<McpToolResult> callTool(String name, Map<String, Object> args) {
                return CompletableFuture.completedFuture(McpToolResult.success(""));
            }

            @Override
            public void close() {}
        };
        return new McpFunctionAdapter(new McpToolRegistry(client));
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
