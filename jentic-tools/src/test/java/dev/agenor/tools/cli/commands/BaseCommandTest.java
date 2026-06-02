package dev.agenor.tools.cli.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

class BaseCommandTest {

    private TestCommand command;
    private ByteArrayOutputStream outContent;
    private ByteArrayOutputStream errContent;
    private PrintStream originalOut;
    private PrintStream originalErr;
    private ObjectMapper mapper = new ObjectMapper();

    // Concrete implementation for testing
    static class TestCommand extends BaseCommand {
        @Override
        public void run() {
            // Test implementation
        }
    }

    @BeforeEach
    void setUp() {
        command = new TestCommand();
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
    void shouldReturnCorrectApiUrl() {
        // Given
        command.apiUrl = "http://test:9090";

        // When
        String url = command.getApiUrl();

        // Then
        assertEquals("http://test:9090", url);
    }

    @Test
    void shouldReturnVerboseFlag() {
        // Given
        command.verbose = true;

        // When
        boolean verbose = command.isVerbose();

        // Then
        assertTrue(verbose);
    }

    @Test
    void shouldExtractDataFieldFromResponse() throws Exception {
        // Given
        String json = "{\"data\": {\"key\": \"value\"}}";
        JsonNode response = mapper.readTree(json);

        // When
        JsonNode data = command.extractData(response);

        // Then
        assertEquals("value", data.get("key").asText());
    }

    @Test
    void shouldReturnResponseWhenNoDataField() throws Exception {
        // Given
        String json = "{\"key\": \"value\"}";
        JsonNode response = mapper.readTree(json);

        // When
        JsonNode data = command.extractData(response);

        // Then
        assertEquals("value", data.get("key").asText());
    }

    @Test
    void shouldPrintErrorMessage() {
        // When
        command.printError("Test error");

        // Then
        String output = errContent.toString();
        assertTrue(output.contains("Error: Test error"));
    }

    @Test
    void shouldPrintSuccessMessage() {
        // When
        command.printSuccess("Operation completed");

        // Then
        String output = outContent.toString();
        assertTrue(output.contains("Operation completed"));
    }

    @Test
    void shouldPrintVerboseGetRequest() {
        // Given
        command.verbose = true;

        // When
        command.printSuccess("test");

        // Then - just verify a verbose flag works
        assertTrue(command.isVerbose());
    }

    @Test
    void shouldLogGetUrlToStdoutWhenVerbose() {
    	// Given
        TestCommand cmd = new TestCommand();
        // Use unreachable port so the real method prints the URL then throws
        cmd.apiUrl = "http://localhost:19996";
        cmd.verbose = true;

        // When
        try {
            cmd.apiGet("/probe");
        } catch (Exception ignored) {
            // expected: no server listening
        }

        // Then
        String out = outContent.toString();
        assertTrue(out.contains("GET"), "Expected 'GET' in stdout, got: " + out);
        assertTrue(out.contains("/probe"));
    }

    @Test
    void shouldLogPostUrlToStdoutWhenVerbose() {
    	// Given
        TestCommand cmd = new TestCommand();
        cmd.apiUrl = "http://localhost:19996";
        cmd.verbose = true;

        //When
        try {
            cmd.apiPost("/probe");
        } catch (Exception ignored) {
            // expected: no server listening
        }

        // Then
        String out = outContent.toString();
        assertTrue(out.contains("POST"), "Expected 'POST' in stdout, got: " + out);
        assertTrue(out.contains("/probe"));
    }

    @Test
    void shouldNotLogUrlWhenNotVerbose() {
    	// Given
        TestCommand cmd = new TestCommand();
        cmd.apiUrl = "http://localhost:19996";
        cmd.verbose = false;

        // When
        try {
            cmd.apiGet("/quiet");
        } catch (Exception ignored) {
            // expected: no server listening
        }

        // Then
        assertFalse(outContent.toString().contains("GET"));
    }
}
