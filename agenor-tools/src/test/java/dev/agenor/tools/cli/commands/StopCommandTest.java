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

class StopCommandTest {

    private StopCommand command;
    private ByteArrayOutputStream outContent;
    private ByteArrayOutputStream errContent;
    private PrintStream originalOut;
    private PrintStream originalErr;
    private ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        command = spy(new StopCommand());
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
    void shouldStopAgentSuccessfully() throws Exception {
        // Given
        String json = """
            {
                "success": true,
                "message": "Agent stopped"
            }
            """;
        JsonNode response = mapper.readTree(json);

        var agentField = StopCommand.class.getDeclaredField("agentId");
        agentField.setAccessible(true);
        agentField.set(command, "test-agent");

        var forceField = StopCommand.class.getDeclaredField("force");
        forceField.setAccessible(true);
        forceField.set(command, false);

        doReturn(response).when(command).apiPost("/api/agents/test-agent/stop");

        // When
        command.run();

        // Then
        String output = outContent.toString();
        assertTrue(output.contains("Agent 'test-agent' stopped successfully"));
    }

    @Test
    void shouldStopAgentWithForceFlag() throws Exception {
        // Given
        String json = """
            {
                "success": true
            }
            """;
        JsonNode response = mapper.readTree(json);

        var agentField = StopCommand.class.getDeclaredField("agentId");
        agentField.setAccessible(true);
        agentField.set(command, "test-agent");

        var forceField = StopCommand.class.getDeclaredField("force");
        forceField.setAccessible(true);
        forceField.set(command, true);

        doReturn(response).when(command).apiPost("/api/agents/test-agent/stop?force=true");

        // When
        command.run();

        // Then
        String output = outContent.toString();
        assertTrue(output.contains("Agent 'test-agent' stopped successfully"));
    }

    @Test
    void shouldHandleStopFailure() throws Exception {
        // Given
        String json = """
            {
                "success": false,
                "message": "Agent not running"
            }
            """;
        JsonNode response = mapper.readTree(json);

        var agentField = StopCommand.class.getDeclaredField("agentId");
        agentField.setAccessible(true);
        agentField.set(command, "test-agent");

        var forceField = StopCommand.class.getDeclaredField("force");
        forceField.setAccessible(true);
        forceField.set(command, false);

        doReturn(response).when(command).apiPost("/api/agents/test-agent/stop");

        // When
        command.run();

        // Then
        String output = errContent.toString();
        assertTrue(output.contains("Failed to stop agent"));
        assertTrue(output.contains("Agent not running"));
    }

    @Test
    void shouldHandleApiException() throws Exception {
        // Given
        var agentField = StopCommand.class.getDeclaredField("agentId");
        agentField.setAccessible(true);
        agentField.set(command, "test-agent");

        var forceField = StopCommand.class.getDeclaredField("force");
        forceField.setAccessible(true);
        forceField.set(command, false);

        doThrow(new RuntimeException("Network error"))
            .when(command).apiPost("/api/agents/test-agent/stop");

        // When
        command.run();

        // Then
        String output = errContent.toString();
        assertTrue(output.contains("Failed to stop agent"));
        assertTrue(output.contains("Network error"));
    }

    @Test
    void shouldPrintStackTraceWhenVerbose() throws Exception {
        // Given
        command.verbose = true;

        var agentField = StopCommand.class.getDeclaredField("agentId");
        agentField.setAccessible(true);
        agentField.set(command, "test-agent");

        var forceField = StopCommand.class.getDeclaredField("force");
        forceField.setAccessible(true);
        forceField.set(command, false);

        doThrow(new RuntimeException("API error"))
            .when(command).apiPost("/api/agents/test-agent/stop");

        // When
        command.run();

        // Then
        String output = errContent.toString();
        assertTrue(output.contains("Failed to stop agent"));
    }

    @Test
    void shouldHandleMissingSuccessField() throws Exception {
        // Given
        String json = "{}";
        JsonNode response = mapper.readTree(json);

        var agentField = StopCommand.class.getDeclaredField("agentId");
        agentField.setAccessible(true);
        agentField.set(command, "test-agent");

        var forceField = StopCommand.class.getDeclaredField("force");
        forceField.setAccessible(true);
        forceField.set(command, false);

        doReturn(response).when(command).apiPost("/api/agents/test-agent/stop");

        // When
        command.run();

        // Then
        String output = errContent.toString();
        assertTrue(output.contains("Failed to stop agent"));
    }

    @Test
    void shouldCallForceEndpointWhenForceFlagSet() throws Exception {
    	// Given
        setField("agentId", "agent-1");
        setField("force", true);
        doReturn(mapper.readTree("{\"success\":true}"))
            .when(command).apiPost("/api/agents/agent-1/stop?force=true");

        // When
        command.run();

        // Then
        verify(command).apiPost("/api/agents/agent-1/stop?force=true");
    }

    @Test
    void shouldCallPlainEndpointWhenForceFlagNotSet() throws Exception {
        // Given
    	setField("agentId", "agent-1");
        setField("force", false);
        doReturn(mapper.readTree("{\"success\":true}"))
            .when(command).apiPost("/api/agents/agent-1/stop");

        // When
        command.run();

        // Then
        verify(command).apiPost("/api/agents/agent-1/stop");
    }

    @Test
    void shouldHandleMissingMessageFieldOnFailure() throws Exception {
    	// Given
        JsonNode response = mapper.readTree("{\"success\":false}");

        setField("agentId", "test-agent");
        setField("force", false);
        doReturn(response).when(command).apiPost("/api/agents/test-agent/stop");

        // When
        command.run();

        // Then
        String output = errContent.toString();
        assertTrue(output.contains("Unknown error"));
    }

    // helper
    private void setField(String name, Object value) throws Exception {
        var f = StopCommand.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(command, value);
    }
}
