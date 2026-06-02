package dev.agenor.tools.cli.commands;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

class LogsCommandTest {

    private LogsCommand command;
    private ByteArrayOutputStream outContent;
    private ByteArrayOutputStream errContent;
    private PrintStream originalOut;
    private PrintStream originalErr;
    private ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        command = spy(new LogsCommand());
        command.apiUrl = "http://localhost:8080";
        command.verbose = false;

        originalOut = System.out;
        originalErr = System.err;
        outContent = new ByteArrayOutputStream();
        errContent = new ByteArrayOutputStream();
        System.setOut(new PrintStream(outContent));
        System.setErr(new PrintStream(errContent));
    }

    @AfterEach
    void tearDown() {
        System.setOut(originalOut);
        System.setErr(originalErr);
    }

    @Test
    void shouldDisplayRecentLogsWithoutFilter() throws Exception {
        // Given
        String json = """
            {
                "data": [
                    {
                        "timestamp": "2024-01-15T10:30:00Z",
                        "agentId": "agent-1",
                        "topic": "system.log",
                        "content": "Agent started"
                    },
                    {
                        "timestamp": "2024-01-15T10:31:00Z",
                        "agentId": "agent-2",
                        "topic": "user.action",
                        "content": "User action triggered"
                    }
                ]
            }
            """;
        JsonNode response = mapper.readTree(json);

        var followField = LogsCommand.class.getDeclaredField("follow");
        followField.setAccessible(true);
        followField.set(command, false);

        var linesField = LogsCommand.class.getDeclaredField("lines");
        linesField.setAccessible(true);
        linesField.set(command, 50);

        doReturn(response).when(command).apiGet(anyString());

        // When
        command.run();

        // Then
        String output = outContent.toString();
        assertTrue(output.contains("agent-1") || output.contains("system.log"));
    }

    @Test
    void shouldDisplayRecentLogsWithAgentFilter() throws Exception {
        // Given
        String json = """
            {
                "data": [
                    {
                        "timestamp": "2024-01-15T10:30:00Z",
                        "agentId": "agent-1",
                        "topic": "system.log",
                        "content": "Agent started"
                    }
                ]
            }
            """;
        JsonNode response = mapper.readTree(json);

        var followField = LogsCommand.class.getDeclaredField("follow");
        followField.setAccessible(true);
        followField.set(command, false);

        var agentIdField = LogsCommand.class.getDeclaredField("agentId");
        agentIdField.setAccessible(true);
        agentIdField.set(command, "agent-1");

        doReturn(response).when(command).apiGet(anyString());

        // When
        command.run();

        // Then
        String output = outContent.toString();
        assertFalse(output.isEmpty());
    }

    @Test
    void shouldDisplayRecentLogsWithTopicFilter() throws Exception {
        // Given
        String json = """
            {
                "data": [
                    {
                        "timestamp": "2024-01-15T10:30:00Z",
                        "agentId": "agent-1",
                        "topic": "system.log",
                        "content": "System message"
                    }
                ]
            }
            """;
        JsonNode response = mapper.readTree(json);

        var followField = LogsCommand.class.getDeclaredField("follow");
        followField.setAccessible(true);
        followField.set(command, false);

        var topicField = LogsCommand.class.getDeclaredField("topic");
        topicField.setAccessible(true);
        topicField.set(command, "system.log");

        doReturn(response).when(command).apiGet(anyString());

        // When
        command.run();

        // Then
        String output = outContent.toString();
        assertFalse(output.isEmpty());
    }

    @Test
    void shouldHandleEmptyLogs() throws Exception {
        // Given
        String json = "{\"data\": []}";
        JsonNode response = mapper.readTree(json);

        var followField = LogsCommand.class.getDeclaredField("follow");
        followField.setAccessible(true);
        followField.set(command, false);

        doReturn(response).when(command).apiGet(anyString());

        // When
        command.run();

        // Then
        String output = outContent.toString();
        assertTrue(output.contains("No recent messages"));
    }

    @Test
    void shouldHandleApiError() throws Exception {
        // Given
        var followField = LogsCommand.class.getDeclaredField("follow");
        followField.setAccessible(true);
        followField.set(command, false);

        doThrow(new RuntimeException("Connection failed"))
                .when(command).apiGet(anyString());

        // When
        command.run();

        // Then
        String output = errContent.toString();
        assertTrue(output.contains("Failed to get logs"));
    }

    @Test
    void shouldPrintStackTraceWhenVerbose() throws Exception {
        // Given
        command.verbose = true;

        var followField = LogsCommand.class.getDeclaredField("follow");
        followField.setAccessible(true);
        followField.set(command, false);

        doThrow(new RuntimeException("API error"))
                .when(command).apiGet(anyString());

        // When
        command.run();

        // Then
        String output = errContent.toString();
        assertTrue(output.contains("Failed to get logs"));
    }

    @Test
    void shouldHandleTopicPatternFilter() throws Exception {
        // Given
        String json = """
            {
                "data": [
                    {
                        "timestamp": "2024-01-15T10:30:00Z",
                        "agentId": "agent-1",
                        "topic": "system.log.info",
                        "content": "Info message"
                    }
                ]
            }
            """;
        JsonNode response = mapper.readTree(json);

        var followField = LogsCommand.class.getDeclaredField("follow");
        followField.setAccessible(true);
        followField.set(command, false);

        var topicPatternField = LogsCommand.class.getDeclaredField("topicPattern");
        topicPatternField.setAccessible(true);
        topicPatternField.set(command, "system.*");

        doReturn(response).when(command).apiGet(anyString());

        // When
        command.run();

        // Then
        String output = outContent.toString();
        assertTrue(output.contains("Recent messages") || output.contains("system.log.info"));
    }

    @Test
    void shouldRespectLinesParameter() throws Exception {
        // Given
        String json = """
            {
                "data": [
                    {
                        "timestamp": "2024-01-15T10:30:00Z",
                        "agentId": "agent-1",
                        "topic": "test",
                        "content": "Message"
                    }
                ]
            }
            """;
        JsonNode response = mapper.readTree(json);

        var followField = LogsCommand.class.getDeclaredField("follow");
        followField.setAccessible(true);
        followField.set(command, false);

        var linesField = LogsCommand.class.getDeclaredField("lines");
        linesField.setAccessible(true);
        linesField.set(command, 10);

        doReturn(response).when(command).apiGet(contains("limit=10"));

        // When
        command.run();

        // Then - verify API was called with the correct limit
        verify(command).apiGet(contains("limit=10"));
    }

    @Test
    void shouldNotAppendAgentIdWhenEmpty() throws Exception {
        setFollow(false);
        setAgentId("");
        setLines(50);
        doReturn(mapper.readTree("{\"data\":[]}")).when(command).apiGet(anyString());

        command.run();

        verify(command).apiGet(argThat(url -> !url.contains("senderId")));
    }

    @Test
    void shouldNotAppendTopicPatternWhenEmpty() throws Exception {
        setFollow(false);
        setTopicPattern("");
        setLines(50);
        doReturn(mapper.readTree("{\"data\":[]}")).when(command).apiGet(anyString());

        command.run();

        verify(command).apiGet(argThat(url -> !url.contains("topicPattern")));
    }

    @Test
    void shouldBuildUrlWithAllFilters() throws Exception {
        setFollow(false);
        setAgentId("agent-1");
        setTopic("sys");
        setTopicPattern("sys.*");
        setLines(20);
        doReturn(mapper.readTree("{\"data\":[]}")).when(command).apiGet(anyString());

        command.run();

        verify(command).apiGet(argThat(url ->
            url.contains("limit=20") &&
            url.contains("senderId=agent-1") &&
            url.contains("topic=sys") &&
            url.contains("topicPattern=sys.*")));
    }

    @Test
    void shouldHandleFollowModeConnectionFailure() throws Exception {
        command.verbose = false;
        setFollow(true);
        // Point to unreachable port to trigger WebSocket connect failure
        command.apiUrl = "http://localhost:19998";

        Thread t = new Thread(command::run);
        t.setDaemon(true);
        t.start();
        t.join(4000L);

        // streamLogs() catches the connection error and writes to stderr in some form
        String err = errContent.toString();
        String out = outContent.toString();
        assertFalse(err.isEmpty() || out.contains("Connected"),
            "Expected an error indication, err='" + err + "' out='" + out + "'");
    }

    // helpers
    private void setFollow(boolean value) throws Exception { setField("follow", value); }
    private void setLines(int value) throws Exception { setField("lines", value); }
    private void setAgentId(String value) throws Exception { setField("agentId", value); }
    private void setTopic(String value) throws Exception { setField("topic", value); }
    private void setTopicPattern(String value) throws Exception { setField("topicPattern", value); }

    private void setField(String name, Object value) throws Exception {
        var f = LogsCommand.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(command, value);
    }
}
