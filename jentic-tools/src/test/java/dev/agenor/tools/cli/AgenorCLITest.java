package dev.agenor.tools.cli;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import picocli.CommandLine;

class AgenorCLITest {

    private ByteArrayOutputStream outContent;
    private ByteArrayOutputStream errContent;
    private PrintStream originalOut;
    private PrintStream originalErr;

    @BeforeEach
    void setUp() {
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
    @DisplayName("run() prints usage to stdout")
    void shouldPrintUsageOnRun() {
        new AgenorCLI().run();
        String output = outContent.toString();
        assertFalse(output.isEmpty());
        assertTrue(output.contains("agenor"), "Expected usage to contain command name 'agenor'");
    }

    @Test
    @DisplayName("execute with no args prints usage and exits 0")
    void shouldExitZeroWithNoArgs() {
        int code = new CommandLine(new AgenorCLI())
                .setCaseInsensitiveEnumValuesAllowed(true)
                .execute();
        assertEquals(0, code);
        assertFalse(outContent.toString().isEmpty());
    }

    @Test
    @DisplayName("execute --help exits 0 and prints help")
    void shouldExitZeroWithHelp() {
        int code = new CommandLine(new AgenorCLI())
                .setCaseInsensitiveEnumValuesAllowed(true)
                .execute("--help");
        assertEquals(0, code);
        assertTrue(outContent.toString().contains("agenor"));
    }

    @Test
    @DisplayName("execute --version exits 0")
    void shouldExitZeroWithVersion() {
        int code = new CommandLine(new AgenorCLI())
                .setCaseInsensitiveEnumValuesAllowed(true)
                .execute("--version");
        assertEquals(0, code);
    }

    @Test
    @DisplayName("execute unknown subcommand exits non-zero")
    void shouldExitNonZeroWithUnknownSubcommand() {
        int code = new CommandLine(new AgenorCLI())
                .setCaseInsensitiveEnumValuesAllowed(true)
                .execute("nonexistentcmd");
        assertNotEquals(0, code);
    }

    @Test
    @DisplayName("execute list subcommand is recognized (exits non-zero without server)")
    void shouldRecognizeListSubcommand() {
        int code = new CommandLine(new AgenorCLI())
                .setCaseInsensitiveEnumValuesAllowed(true)
                .execute("list");
        // list tries to connect to server - will fail, but picocli exits with 1 (not usage error)
        // The subcommand itself is recognized (exit != 2 which is picocli's usage error code)
        assertNotEquals(2, code);
    }

    @Test
    @DisplayName("execute status subcommand is recognized")
    void shouldRecognizeStatusSubcommand() {
        int code = new CommandLine(new AgenorCLI())
                .setCaseInsensitiveEnumValuesAllowed(true)
                .execute("status");
        assertNotEquals(2, code);
    }

    @Test
    @DisplayName("execute health subcommand is recognized")
    void shouldRecognizeHealthSubcommand() {
        int code = new CommandLine(new AgenorCLI())
                .setCaseInsensitiveEnumValuesAllowed(true)
                .execute("health");
        assertNotEquals(2, code);
    }

    @Test
    @DisplayName("execute config subcommand is recognized")
    void shouldRecognizeConfigSubcommand() {
        int code = new CommandLine(new AgenorCLI())
                .setCaseInsensitiveEnumValuesAllowed(true)
                .execute("config");
        // config show with no server should print default config info, not usage error
        assertNotEquals(2, code);
    }

    @Test
    @DisplayName("execute start requires agentId parameter")
    void shouldRequireAgentIdForStart() {
        int code = new CommandLine(new AgenorCLI())
                .setCaseInsensitiveEnumValuesAllowed(true)
                .execute("start");
        // Missing required parameter -> picocli exits 2
        assertEquals(2, code);
    }

    @Test
    @DisplayName("execute stop requires agentId parameter")
    void shouldRequireAgentIdForStop() {
        int code = new CommandLine(new AgenorCLI())
                .setCaseInsensitiveEnumValuesAllowed(true)
                .execute("stop");
        assertEquals(2, code);
    }
}
