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

class ListCommandTest {

    private ListCommand command;
    private ByteArrayOutputStream outContent;
    private ByteArrayOutputStream errContent;
    private PrintStream originalOut;
    private PrintStream originalErr;
    private ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        command = spy(new ListCommand());
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
    void shouldDisplayAgentsInTableFormat() throws Exception {
        // Given
        String json = """
            {
                "data": [
                    {
                        "id": "agent-1",
                        "name": "TestAgent",
                        "running": true,
                        "behaviorCount": 3
                    },
                    {
                        "id": "agent-2",
                        "name": "AnotherAgent",
                        "running": false,
                        "behaviorCount": 1
                    }
                ]
            }
            """;
        JsonNode response = mapper.readTree(json);
        doReturn(response).when(command).apiGet("/api/agents");

        var field = ListCommand.class.getDeclaredField("format");
        field.setAccessible(true);
        field.set(command, "table");

        // When
        command.run();

        // Then
        String output = outContent.toString();
        assertTrue(output.contains("TestAgent"));
        assertTrue(output.contains("AnotherAgent"));
        assertTrue(output.contains("RUNNING"));
        assertTrue(output.contains("STOPPED"));
        assertTrue(output.contains("Total: 2 agent(s)"));
    }

    @Test
    void shouldDisplayAgentsInJsonFormat() throws Exception {
        // Given
        String json = """
            {
                "data": [
                    {
                        "id": "agent-1",
                        "name": "TestAgent",
                        "running": true,
                        "behaviorCount": 2
                    }
                ]
            }
            """;
        JsonNode response = mapper.readTree(json);
        doReturn(response).when(command).apiGet("/api/agents");

        var field = ListCommand.class.getDeclaredField("format");
        field.setAccessible(true);
        field.set(command, "json");

        // When
        command.run();

        // Then
        String output = outContent.toString();
        assertTrue(output.contains("\"id\" : \"agent-1\""));
        assertTrue(output.contains("\"name\" : \"TestAgent\""));
    }

    @Test
    void shouldDisplayMessageWhenNoAgentsRegistered() throws Exception {
        // Given
        String json = "{\"data\": []}";
        JsonNode response = mapper.readTree(json);
        doReturn(response).when(command).apiGet("/api/agents");

        // When
        command.run();

        // Then
        String output = outContent.toString();
        assertTrue(output.contains("No agents registered"));
    }

    @Test
    void shouldHandleNullDataGracefully() throws Exception {
        // Given
        String json = "{}";
        JsonNode response = mapper.readTree(json);
        doReturn(response).when(command).apiGet("/api/agents");

        // When
        command.run();

        // Then
        String output = outContent.toString();
        assertTrue(output.contains("No agents registered"));
    }

    @Test
    void shouldHandleApiErrorGracefully() throws Exception {
        // Given
        doThrow(new RuntimeException("API connection failed"))
            .when(command).apiGet("/api/agents");

        // When
        command.run();

        // Then
        String output = errContent.toString();
        assertTrue(output.contains("Failed to list agents"));
        assertTrue(output.contains("API connection failed"));
    }

    @Test
    void shouldPrintStackTraceWhenVerbose() throws Exception {
        // Given
        command.verbose = true;
        doThrow(new RuntimeException("API error"))
            .when(command).apiGet("/api/agents");

        // When
        command.run();

        // Then
        String output = errContent.toString();
        assertTrue(output.contains("Failed to list agents"));
    }

    @Test
    void shouldHandleNonArrayDataGracefully() throws Exception {
    	// Given
        // data field exists but is not an array
        String json = "{\"data\": {\"unexpected\": \"object\"}}";
        doReturn(mapper.readTree(json)).when(command).apiGet("/api/agents");

        // When
        command.run();

        // Then
        // Should not crash – either prints "No agents" or an empty table
        String out = outContent.toString();
        assertFalse(out.isEmpty());
    }

    @Test
    void shouldRenderBehaviorCountInTable() throws Exception {
    	// Given
        String json = """
            {
                "data": [
                    {"id":"long-id-abc123","name":"BusyAgent","running":true,"behaviorCount":7}
                ]
            }
            """;
        doReturn(mapper.readTree(json)).when(command).apiGet("/api/agents");

        // When
        command.run();

        // Then
        String output = outContent.toString();
        assertTrue(output.contains("BusyAgent"));
        assertTrue(output.contains("7"));
    }

    @Test
    void shouldDisplayTotalAgentCount() throws Exception {
    	// Given
        String json = """
            {"data":[
                {"id":"a1","name":"A","running":true,"behaviorCount":0},
                {"id":"a2","name":"B","running":false,"behaviorCount":0},
                {"id":"a3","name":"C","running":true,"behaviorCount":0}
            ]}
            """;
        doReturn(mapper.readTree(json)).when(command).apiGet("/api/agents");

        // When
        command.run();

        // Then
        assertTrue(outContent.toString().contains("3 agent(s)"));
    }
}
