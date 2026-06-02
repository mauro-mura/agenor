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

class HealthCommandTest {

    private HealthCommand command;
    private ByteArrayOutputStream outContent;
    private ByteArrayOutputStream errContent;
    private PrintStream originalOut;
    private PrintStream originalErr;
    private ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        command = spy(new HealthCommand());
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
    void shouldDisplayHealthyStatus() throws Exception {
        // Given
        String healthJson = """
            {
                "data": {
                    "status": "UP"
                }
            }
            """;
        String statsJson = """
            {
                "data": {
                    "runtimeName": "test-runtime",
                    "totalAgents": 5,
                    "activeAgents": 3,
                    "threads": 42,
                    "memory": {
                        "used": 104857600,
                        "max": 536870912
                    }
                }
            }
            """;

        JsonNode healthResponse = mapper.readTree(healthJson);
        JsonNode statsResponse = mapper.readTree(statsJson);

        doReturn(healthResponse).when(command).apiGet("/api/health");
        doReturn(statsResponse).when(command).apiGet("/api/stats");

        var field = HealthCommand.class.getDeclaredField("watch");
        field.setAccessible(true);
        field.set(command, false);

        // When
        command.run();

        // Then
        String output = outContent.toString();
        assertTrue(output.contains("Status:"));
        assertTrue(output.contains("UP"));
        assertTrue(output.contains("Runtime is healthy"));
        assertTrue(output.contains("test-runtime"));
        assertTrue(output.contains("3/5 running"));
    }

    @Test
    void shouldDisplayUnhealthyStatus() throws Exception {
        // Given
        String healthJson = """
            {
                "data": {
                    "status": "DOWN"
                }
            }
            """;
        String statsJson = """
            {
                "data": {
                    "runtimeName": "test-runtime",
                    "totalAgents": 0,
                    "activeAgents": 0
                }
            }
            """;

        JsonNode healthResponse = mapper.readTree(healthJson);
        JsonNode statsResponse = mapper.readTree(statsJson);

        doReturn(healthResponse).when(command).apiGet("/api/health");
        doReturn(statsResponse).when(command).apiGet("/api/stats");

        // When
        command.run();

        // Then
        String output = outContent.toString();
        assertTrue(output.contains("DOWN"));
        String errOutput = errContent.toString();
        assertTrue(errOutput.contains("Runtime is unhealthy"));
    }

    @Test
    void shouldDisplayMemoryBarWithPercentage() throws Exception {
        // Given
        String healthJson = """
            {
                "data": {
                    "status": "UP"
                }
            }
            """;
        String statsJson = """
            {
                "data": {
                    "runtimeName": "test",
                    "totalAgents": 1,
                    "activeAgents": 1,
                    "memory": {
                        "used": 268435456,
                        "max": 536870912
                    }
                }
            }
            """;

        JsonNode healthResponse = mapper.readTree(healthJson);
        JsonNode statsResponse = mapper.readTree(statsJson);

        doReturn(healthResponse).when(command).apiGet("/api/health");
        doReturn(statsResponse).when(command).apiGet("/api/stats");

        // When
        command.run();

        // Then
        String output = outContent.toString();
        assertTrue(output.contains("Memory:"));
        assertTrue(output.contains("50%") || output.contains("256 MB / 512 MB"));
    }

    @Test
    void shouldHandleApiErrorGracefully() throws Exception {
        // Given
        doThrow(new RuntimeException("Connection refused"))
            .when(command).apiGet("/api/health");

        // When
        command.run();

        // Then
        String output = errContent.toString();
        assertTrue(output.contains("Health check failed"));
        assertTrue(output.contains("Connection refused"));
    }

    @Test
    void shouldPrintStackTraceWhenVerbose() throws Exception {
        // Given
        command.verbose = true;
        doThrow(new RuntimeException("API error"))
            .when(command).apiGet("/api/health");

        // When
        command.run();

        // Then
        String output = errContent.toString();
        assertTrue(output.contains("Health check failed"));
    }

    @Test
    void shouldHandleMissingMemoryData() throws Exception {
        // Given
        String healthJson = """
            {
                "data": {
                    "status": "UP"
                }
            }
            """;
        String statsJson = """
            {
                "data": {
                    "runtimeName": "test",
                    "totalAgents": 1,
                    "activeAgents": 1
                }
            }
            """;

        JsonNode healthResponse = mapper.readTree(healthJson);
        JsonNode statsResponse = mapper.readTree(statsJson);

        doReturn(healthResponse).when(command).apiGet("/api/health");
        doReturn(statsResponse).when(command).apiGet("/api/stats");

        // When
        command.run();

        // Then
        String output = outContent.toString();
        assertTrue(output.contains("Status:"));
        // Should not crash without memory data
    }

    @Test
    void shouldHandleZeroMaxMemory() throws Exception {
        // Given
        String healthJson = """
            {
                "data": {
                    "status": "UP"
                }
            }
            """;
        String statsJson = """
            {
                "data": {
                    "runtimeName": "test",
                    "totalAgents": 1,
                    "activeAgents": 1,
                    "memory": {
                        "used": 100,
                        "max": 0
                    }
                }
            }
            """;

        JsonNode healthResponse = mapper.readTree(healthJson);
        JsonNode statsResponse = mapper.readTree(statsJson);

        doReturn(healthResponse).when(command).apiGet("/api/health");
        doReturn(statsResponse).when(command).apiGet("/api/stats");

        // When
        command.run();

        // Then - should not crash with division by zero
        String output = outContent.toString();
        assertTrue(output.contains("Status:"));
    }

    @Test
    void shouldWatchLoopUntilInterrupted() throws Exception {
        stubHealth("{\"data\":{\"status\":\"UP\"}}");
        stubStats("{\"data\":{\"runtimeName\":\"r\",\"totalAgents\":0,\"activeAgents\":0}}");
        setWatch(true);
        setInterval(1);

        Thread t = new Thread(command::run);
        t.setDaemon(true);
        t.start();
        Thread.sleep(300L);
        t.interrupt();
        t.join(2000L);

        verify(command, atLeastOnce()).apiGet("/api/health");
    }

    @Test
    void shouldWatchContinueAfterCheckHealthException() throws Exception {
        doThrow(new RuntimeException("transient"))
            .doReturn(mapper.readTree("{\"data\":{\"status\":\"UP\"}}"))
            .when(command).apiGet("/api/health");
        stubStats("{\"data\":{\"runtimeName\":\"r\",\"totalAgents\":0,\"activeAgents\":0}}");
        setWatch(true);
        setInterval(1);

        Thread t = new Thread(command::run);
        t.setDaemon(true);
        t.start();
        Thread.sleep(400L);
        t.interrupt();
        t.join(2000L);

        assertTrue(errContent.toString().contains("transient"));
    }

    // helpers
    private void stubHealth(String json) throws Exception {
        doReturn(mapper.readTree(json)).when(command).apiGet("/api/health");
    }

    private void stubStats(String json) throws Exception {
        doReturn(mapper.readTree(json)).when(command).apiGet("/api/stats");
    }

    private void setWatch(boolean value) throws Exception {
        setField("watch", value);
    }

    private void setInterval(int value) throws Exception {
        setField("interval", value);
    }

    private void setField(String name, Object value) throws Exception {
        var f = HealthCommand.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(command, value);
    }
}
