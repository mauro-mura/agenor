package dev.agenor.tools.cli.commands;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfigCommandTest {

    private ConfigCommand command;
    private ByteArrayOutputStream outContent;
    private ByteArrayOutputStream errContent;
    private PrintStream originalOut;
    private PrintStream originalErr;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        command = new ConfigCommand();
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
    void shouldShowConfigWhenFileExists() throws Exception {
        // Given
        Path configFile = tempDir.resolve("agenor.yaml");
        String content = "agenor:\n  runtime:\n    name: test";
        Files.writeString(configFile, content);

        // Use reflection to set configPath
        var field = ConfigCommand.class.getDeclaredField("configPath");
        field.setAccessible(true);
        field.set(command, configFile);

        var actionField = ConfigCommand.class.getDeclaredField("action");
        actionField.setAccessible(true);
        actionField.set(command, "show");

        // When
        command.run();

        // Then
        String output = outContent.toString();
        assertTrue(output.contains(configFile.toString()));
        assertTrue(output.contains(content));
    }

    @Test
    void shouldShowDefaultConfigWhenFileDoesNotExist() throws Exception {
        // Given
        var actionField = ConfigCommand.class.getDeclaredField("action");
        actionField.setAccessible(true);
        actionField.set(command, "show");

        // When
        command.run();

        // Then
        String output = outContent.toString();
        assertTrue(output.contains("No configuration file found"));
        assertTrue(output.contains("Default configuration"));
    }

    @Test
    void shouldValidateExistingConfig() throws Exception {
        // Given
        Path configFile = tempDir.resolve("agenor.yaml");
        String content = "agenor:\n  runtime:\n    name: test\nagents:\n  - id: test";
        Files.writeString(configFile, content);

        var field = ConfigCommand.class.getDeclaredField("configPath");
        field.setAccessible(true);
        field.set(command, configFile);

        var actionField = ConfigCommand.class.getDeclaredField("action");
        actionField.setAccessible(true);
        actionField.set(command, "validate");

        // When
        command.run();

        // Then
        String output = outContent.toString();
        assertTrue(output.contains("Configuration is valid") || output.contains("agenor section"));
    }

    @Test
    void shouldShowErrorForInvalidAction() throws Exception {
        // Given
        var actionField = ConfigCommand.class.getDeclaredField("action");
        actionField.setAccessible(true);
        actionField.set(command, "invalid");

        // When
        command.run();

        // Then
        String output = errContent.toString();
        assertTrue(output.contains("Unknown action"));
    }

    @Test
    void shouldShowConfigPath() throws Exception {
        // Given
        Path configFile = tempDir.resolve("agenor.yaml");
        Files.writeString(configFile, "test");

        var field = ConfigCommand.class.getDeclaredField("configPath");
        field.setAccessible(true);
        field.set(command, configFile);

        var actionField = ConfigCommand.class.getDeclaredField("action");
        actionField.setAccessible(true);
        actionField.set(command, "path");

        // When
        command.run();

        // Then
        String output = outContent.toString();
        assertTrue(output.contains(configFile.toAbsolutePath().toString()));
    }

    @Test
    void shouldShowSearchedLocationsWhenConfigNotFound() throws Exception {
        // Given
        var actionField = ConfigCommand.class.getDeclaredField("action");
        actionField.setAccessible(true);
        actionField.set(command, "path");

        // When
        command.run();

        // Then
        String output = outContent.toString();
        assertTrue(output.contains("No configuration file found"));
        assertTrue(output.contains("Searched locations"));
    }

    @Test
    void shouldHandleExceptionGracefully() throws Exception {
        // Given
        command.verbose = false;
        var actionField = ConfigCommand.class.getDeclaredField("action");
        actionField.setAccessible(true);
        actionField.set(command, "validate");

        // Set an invalid config path to trigger an exception
        var field = ConfigCommand.class.getDeclaredField("configPath");
        field.setAccessible(true);
        field.set(command, Path.of("/nonexistent/path/config.yaml"));

        // When
        command.run();

        // Then
        String output = errContent.toString();
        assertTrue(output.contains("Configuration error") || output.contains("No configuration file"));
    }

    @Test
    void shouldPrintStackTraceWhenVerbose() throws Exception {
        // Given
        command.verbose = true;
        var actionField = ConfigCommand.class.getDeclaredField("action");
        actionField.setAccessible(true);
        actionField.set(command, null); // This will cause NPE

        // When
        command.run();

        // Then - just verify it doesn't crash completely
        String output = errContent.toString();
        assertTrue(output.contains("Configuration error"));
    }

    @Test
    void shouldShowConfigContentWhenVerbose() throws Exception {
        Path configFile = tempDir.resolve("agenor.yaml");
        String content = "agenor:\n  runtime:\n    name: verbose-test\n";
        Files.writeString(configFile, content);

        command.verbose = true;
        setField("configPath", configFile);
        setField("action", "show");

        command.run();

        assertTrue(outContent.toString().contains("agenor"));
    }

    @Test
    void shouldValidateAndSucceedWithMinimalValidYaml() throws Exception {
        Path configFile = tempDir.resolve("agenor.yaml");
        Files.writeString(configFile, "agenor:\n  runtime:\n    name: minimal\n");

        setField("configPath", configFile);
        setField("action", "validate");

        command.run();

        // No error output expected
        String err = errContent.toString();
        assertFalse(err.contains("Configuration error"), "Unexpected error: " + err);
    }

    @Test
    void shouldShowDefaultConfigBodyWhenNoFileExists() throws Exception {
        setField("action", "show");

        command.run();

        // Default config block should contain "agenor-runtime"
        assertTrue(outContent.toString().contains("agenor-runtime"));
    }

    // helper
    private void setField(String name, Object value) throws Exception {
        var f = ConfigCommand.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(command, value);
    }
}
