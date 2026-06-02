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

class StartCommandTest {

    private StartCommand command;
    private ByteArrayOutputStream outContent;
    private ByteArrayOutputStream errContent;
    private PrintStream originalOut;
    private PrintStream originalErr;
    private ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        command = spy(new StartCommand());
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
    void shouldStartAgentSuccessfully() throws Exception {
        // Given
        String json = """
            {
                "success": true,
                "message": "Agent started"
            }
            """;
        JsonNode response = mapper.readTree(json);

        var field = StartCommand.class.getDeclaredField("agentId");
        field.setAccessible(true);
        field.set(command, "test-agent");

        doReturn(response).when(command).apiPost("/api/agents/test-agent/start");

        // When
        command.run();

        // Then
        String output = outContent.toString();
        assertTrue(output.contains("Agent 'test-agent' started successfully"));
    }

    @Test
    void shouldHandleStartFailure() throws Exception {
        // Given
        String json = """
            {
                "success": false,
                "message": "Agent not found"
            }
            """;
        JsonNode response = mapper.readTree(json);

        var field = StartCommand.class.getDeclaredField("agentId");
        field.setAccessible(true);
        field.set(command, "unknown-agent");

        doReturn(response).when(command).apiPost("/api/agents/unknown-agent/start");

        // When
        command.run();

        // Then
        String output = errContent.toString();
        assertTrue(output.contains("Failed to start agent"));
        assertTrue(output.contains("Agent not found"));
    }

    @Test
    void shouldHandleApiException() throws Exception {
        // Given
        var field = StartCommand.class.getDeclaredField("agentId");
        field.setAccessible(true);
        field.set(command, "test-agent");

        doThrow(new RuntimeException("Connection timeout"))
            .when(command).apiPost("/api/agents/test-agent/start");

        // When
        command.run();

        // Then
        String output = errContent.toString();
        assertTrue(output.contains("Failed to start agent"));
        assertTrue(output.contains("Connection timeout"));
    }

    @Test
    void shouldPrintStackTraceWhenVerbose() throws Exception {
        // Given
        command.verbose = true;
        var field = StartCommand.class.getDeclaredField("agentId");
        field.setAccessible(true);
        field.set(command, "test-agent");

        doThrow(new RuntimeException("API error"))
            .when(command).apiPost("/api/agents/test-agent/start");

        // When
        command.run();

        // Then
        String output = errContent.toString();
        assertTrue(output.contains("Failed to start agent"));
    }

    @Test
    void shouldHandleMissingSuccessField() throws Exception {
        // Given
        String json = """
            {
                "message": "Response without success field"
            }
            """;
        JsonNode response = mapper.readTree(json);

        var field = StartCommand.class.getDeclaredField("agentId");
        field.setAccessible(true);
        field.set(command, "test-agent");

        doReturn(response).when(command).apiPost("/api/agents/test-agent/start");

        // When
        command.run();

        // Then
        String output = errContent.toString();
        assertTrue(output.contains("Failed to start agent"));
    }

    @Test
    void shouldHandleMissingMessageField() throws Exception {
        // Given
        String json = """
            {
                "success": false
            }
            """;
        JsonNode response = mapper.readTree(json);

        var field = StartCommand.class.getDeclaredField("agentId");
        field.setAccessible(true);
        field.set(command, "test-agent");

        doReturn(response).when(command).apiPost("/api/agents/test-agent/start");

        // When
        command.run();

        // Then
        String output = errContent.toString();
        assertTrue(output.contains("Failed to start agent"));
        assertTrue(output.contains("Unknown error"));
    }

    @Test
    void shouldIncludeAgentIdInSuccessMessage() throws Exception {
        setField("agentId", "my-agent");
        doReturn(mapper.readTree("{\"success\":true}"))
            .when(command).apiPost("/api/agents/my-agent/start");

        command.run();

        assertTrue(outContent.toString().contains("my-agent"));
    }

    // helper
    private void setField(String name, Object value) throws Exception {
        var f = StartCommand.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(command, value);
    }
}
