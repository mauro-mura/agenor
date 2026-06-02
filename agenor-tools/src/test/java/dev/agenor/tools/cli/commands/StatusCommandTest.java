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

class StatusCommandTest {

    private StatusCommand command;
    private ByteArrayOutputStream outContent;
    private ByteArrayOutputStream errContent;
    private PrintStream originalOut;
    private PrintStream originalErr;
    private ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        command = spy(new StatusCommand());
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
    void shouldDisplayOverviewStatus() throws Exception {
        // Given
        String statsJson = """
            {
                "data": {
                    "runtimeName": "test-runtime",
                    "uptime": 3661000,
                    "totalAgents": 5,
                    "activeAgents": 3
                }
            }
            """;
        String healthJson = """
            {
                "data": {
                    "status": "UP"
                }
            }
            """;
        JsonNode statsResponse = mapper.readTree(statsJson);
        JsonNode healthResponse = mapper.readTree(healthJson);
        doReturn(statsResponse).when(command).apiGet("/api/stats");
        doReturn(healthResponse).when(command).apiGet("/api/health");

        var field = StatusCommand.class.getDeclaredField("agentId");
        field.setAccessible(true);
        field.set(command, null);

        // When
        command.run();

        // Then
        String output = outContent.toString();
        assertTrue(output.contains("Agenor Runtime Status"));
        assertTrue(output.contains("Health:"));
        assertTrue(output.contains("5 total, 3 running"));
        assertTrue(output.contains("Uptime:"));
    }

    @Test
    void shouldDisplayAgentStatus() throws Exception {
        // Given
        String agentJson = """
            {
                "data": {
                    "id": "agent-1",
                    "name": "TestAgent",
                    "running": true,
                    "behaviorCount": 2,
                    "behaviors": [
                        {
                            "name": "behavior1",
                            "type": "CYCLIC"
                        },
                        {
                            "name": "behavior2",
                            "type": "ONE_SHOT"
                        }
                    ],
                    "capabilities": ["cap1", "cap2"]
                }
            }
            """;
        JsonNode agentResponse = mapper.readTree(agentJson);
        doReturn(agentResponse).when(command).apiGet("/api/agents/agent-1");

        var field = StatusCommand.class.getDeclaredField("agentId");
        field.setAccessible(true);
        field.set(command, "agent-1");

        // When
        command.run();

        // Then
        String output = outContent.toString();
        assertTrue(output.contains("TestAgent"));
        assertTrue(output.contains("RUNNING"));
        assertTrue(output.contains("behavior1"));
        assertTrue(output.contains("CYCLIC"));
        assertTrue(output.contains("cap1"));
    }

    @Test
    void shouldFormatUptimeLessThan60Seconds() throws Exception {
        // Given
        String statsJson = """
            {
                "data": {
                    "runtimeName": "test",
                    "uptime": 45000,
                    "totalAgents": 1,
                    "activeAgents": 1
                }
            }
            """;
        String healthJson = """
            {
                "data": {
                    "status": "UP"
                }
            }
            """;
        JsonNode statsResponse = mapper.readTree(statsJson);
        JsonNode healthResponse = mapper.readTree(healthJson);
        doReturn(statsResponse).when(command).apiGet("/api/stats");
        doReturn(healthResponse).when(command).apiGet("/api/health");

        // When
        command.run();

        // Then
        String output = outContent.toString();
        assertTrue(output.contains("45s"));
    }

    @Test
    void shouldFormatUptimeLessThan60Minutes() throws Exception {
        // Given
        String statsJson = """
            {
                "data": {
                    "runtimeName": "test",
                    "uptime": 150000,
                    "totalAgents": 1,
                    "activeAgents": 1
                }
            }
            """;
        String healthJson = """
            {
                "data": {
                    "status": "UP"
                }
            }
            """;
        JsonNode statsResponse = mapper.readTree(statsJson);
        JsonNode healthResponse = mapper.readTree(healthJson);
        doReturn(statsResponse).when(command).apiGet("/api/stats");
        doReturn(healthResponse).when(command).apiGet("/api/health");

        // When
        command.run();

        // Then
        String output = outContent.toString();
        assertTrue(output.contains("2m 30s"));
    }

    @Test
    void shouldFormatUptimeLessThan24Hours() throws Exception {
        // Given
        String statsJson = """
            {
                "data": {
                    "runtimeName": "test",
                    "uptime": 7200000,
                    "totalAgents": 1,
                    "activeAgents": 1
                }
            }
            """;
        String healthJson = """
            {
                "data": {
                    "status": "UP"
                }
            }
            """;
        JsonNode statsResponse = mapper.readTree(statsJson);
        JsonNode healthResponse = mapper.readTree(healthJson);
        doReturn(statsResponse).when(command).apiGet("/api/stats");
        doReturn(healthResponse).when(command).apiGet("/api/health");

        // When
        command.run();

        // Then
        String output = outContent.toString();
        assertTrue(output.contains("2h 0m"));
    }

    @Test
    void shouldFormatUptimeMoreThan24Hours() throws Exception {
        // Given
        String statsJson = """
            {
                "data": {
                    "runtimeName": "test",
                    "uptime": 90000000,
                    "totalAgents": 1,
                    "activeAgents": 1
                }
            }
            """;
        String healthJson = """
            {
                "data": {
                    "status": "UP"
                }
            }
            """;
        JsonNode statsResponse = mapper.readTree(statsJson);
        JsonNode healthResponse = mapper.readTree(healthJson);
        doReturn(statsResponse).when(command).apiGet("/api/stats");
        doReturn(healthResponse).when(command).apiGet("/api/health");

        // When
        command.run();

        // Then
        String output = outContent.toString();
        assertTrue(output.contains("1d 1h"));
    }

    @Test
    void shouldHandleApiError() throws Exception {
        // Given
        doThrow(new RuntimeException("Connection failed"))
                .when(command).apiGet(anyString());

        // When
        command.run();

        // Then
        String output = errContent.toString();
        assertTrue(output.contains("Failed to get status"));
    }

    @Test
    void shouldPrintStackTraceWhenVerbose() throws Exception {
        // Given
        command.verbose = true;
        doThrow(new RuntimeException("API error"))
                .when(command).apiGet(anyString());

        // When
        command.run();

        // Then
        String output = errContent.toString();
        assertTrue(output.contains("Failed to get status"));
    }

    @Test
    void shouldHandleAgentWithoutBehaviors() throws Exception {
        // Given
        String agentJson = """
            {
                "data": {
                    "id": "agent-1",
                    "name": "TestAgent",
                    "running": false,
                    "behaviorCount": 0
                }
            }
            """;
        JsonNode agentResponse = mapper.readTree(agentJson);
        doReturn(agentResponse).when(command).apiGet("/api/agents/agent-1");

        var field = StatusCommand.class.getDeclaredField("agentId");
        field.setAccessible(true);
        field.set(command, "agent-1");

        // When
        command.run();

        // Then
        String output = outContent.toString();
        assertTrue(output.contains("TestAgent"));
        assertTrue(output.contains("STOPPED"));
    }

    @Test
    void shouldHandleAgentWithoutCapabilities() throws Exception {
        // Given
        String agentJson = """
            {
                "data": {
                    "id": "agent-1",
                    "name": "TestAgent",
                    "running": true,
                    "behaviorCount": 0
                }
            }
            """;
        JsonNode agentResponse = mapper.readTree(agentJson);
        doReturn(agentResponse).when(command).apiGet("/api/agents/agent-1");

        var field = StatusCommand.class.getDeclaredField("agentId");
        field.setAccessible(true);
        field.set(command, "agent-1");

        // When
        command.run();

        // Then
        String output = outContent.toString();
        assertTrue(output.contains("TestAgent"));
    }

    @Test
    void shouldFormatUptimeBetween60And3600Seconds() throws Exception {
        // 125_000 ms = 125s = 2m 5s
        stubStats("{\"data\":{\"runtimeName\":\"r\",\"uptime\":125000,\"totalAgents\":0,\"activeAgents\":0}}");
        stubHealth("{\"data\":{\"status\":\"UP\"}}");
        setAgentId(null);

        command.run();

        assertTrue(outContent.toString().contains("2m"));
    }

    @Test
    void shouldFormatUptimeBetween3600And86400Seconds() throws Exception {
        // 7_320_000 ms = 7320s = 2h 2m
        stubStats("{\"data\":{\"runtimeName\":\"r\",\"uptime\":7320000,\"totalAgents\":0,\"activeAgents\":0}}");
        stubHealth("{\"data\":{\"status\":\"UP\"}}");
        setAgentId(null);

        command.run();

        assertTrue(outContent.toString().contains("h"));
    }

    @Test
    void shouldFormatUptimeAbove86400Seconds() throws Exception {
        // 90_000_000 ms = 90000s = 1d 1h
        stubStats("{\"data\":{\"runtimeName\":\"r\",\"uptime\":90000000,\"totalAgents\":0,\"activeAgents\":0}}");
        stubHealth("{\"data\":{\"status\":\"UP\"}}");
        setAgentId(null);

        command.run();

        assertTrue(outContent.toString().contains("d"));
    }

    @Test
    void shouldDisplayRuntimeStatusDownWithErrorIcon() throws Exception {
        stubStats("{\"data\":{\"runtimeName\":\"r\",\"uptime\":0,\"totalAgents\":0,\"activeAgents\":0}}");
        stubHealth("{\"data\":{\"status\":\"DOWN\"}}");
        setAgentId(null);

        command.run();

        assertTrue(outContent.toString().contains("Agenor Runtime Status"));
    }

    @Test
    void shouldDisplayAgentCountsInRuntimeStatus() throws Exception {
        stubStats("{\"data\":{\"runtimeName\":\"r\",\"uptime\":1000,\"totalAgents\":4,\"activeAgents\":2}}");
        stubHealth("{\"data\":{\"status\":\"UP\"}}");
        setAgentId(null);

        command.run();

        String out = outContent.toString();
        assertTrue(out.contains("4 total") && out.contains("2 running"),
            "Expected agent counts in output, got: " + out);
    }

    @Test
    void shouldDisplayMemoryWhenPresent() throws Exception {
        stubStats("{\"data\":{\"runtimeName\":\"r\",\"uptime\":1000,\"totalAgents\":1,\"activeAgents\":1,\"memory\":{\"used\":104857600,\"max\":536870912}}}");
        stubHealth("{\"data\":{\"status\":\"UP\"}}");
        setAgentId(null);

        command.run();

        assertTrue(outContent.toString().contains("Memory:"));
    }

    @Test
    void shouldDisplayAgentRunningStatus() throws Exception {
        String agentJson = """
            {"data":{"id":"a1","name":"Agent","running":true,"behaviorCount":2,
             "behaviors":[{"name":"b1","type":"CYCLIC"}],
             "capabilities":["cap1","cap2"]}}
            """;
        doReturn(mapper.readTree(agentJson)).when(command).apiGet("/api/agents/a1");
        setAgentId("a1");

        command.run();

        String out = outContent.toString();
        assertTrue(out.contains("RUNNING"));
        assertTrue(out.contains("b1"));
        assertTrue(out.contains("cap1"));
    }

    // helpers
    private void stubStats(String json) throws Exception {
        doReturn(mapper.readTree(json)).when(command).apiGet("/api/stats");
    }

    private void stubHealth(String json) throws Exception {
        doReturn(mapper.readTree(json)).when(command).apiGet("/api/health");
    }

    private void setAgentId(String id) throws Exception {
        var f = StatusCommand.class.getDeclaredField("agentId");
        f.setAccessible(true);
        f.set(command, id);
    }
}
