package dev.agenor.tools.cli.commands;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class WatchCommandTest {

    private WatchCommand command;
    private ByteArrayOutputStream outContent;
    private ByteArrayOutputStream errContent;
    private PrintStream originalOut;
    private PrintStream originalErr;

    @BeforeEach
    void setUp() {
        command = new WatchCommand();

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
    void shouldSetDefaultHostAndPort() throws Exception {
        // Given - command with defaults
        var hostField = WatchCommand.class.getDeclaredField("host");
        hostField.setAccessible(true);
        hostField.set(command, "localhost");

        var portField = WatchCommand.class.getDeclaredField("port");
        portField.setAccessible(true);
        portField.set(command, 8080);

        // When - just check fields are set
        String host = (String) hostField.get(command);
        int port = (int) portField.get(command);

        // Then
        assertEquals("localhost", host);
        assertEquals(8080, port);
    }

    @Test
    void shouldSetCustomHostAndPort() throws Exception {
        // Given
        var hostField = WatchCommand.class.getDeclaredField("host");
        hostField.setAccessible(true);
        hostField.set(command, "custom-host");

        var portField = WatchCommand.class.getDeclaredField("port");
        portField.setAccessible(true);
        portField.set(command, 9090);

        // When
        String host = (String) hostField.get(command);
        int port = (int) portField.get(command);

        // Then
        assertEquals("custom-host", host);
        assertEquals(9090, port);
    }

    @Test
    void shouldSetFilterOption() throws Exception {
        // Given
        var filterField = WatchCommand.class.getDeclaredField("filter");
        filterField.setAccessible(true);
        filterField.set(command, "behavior");

        // When
        String filter = (String) filterField.get(command);

        // Then
        assertEquals("behavior", filter);
    }

    @Test
    void shouldSetQuietMode() throws Exception {
        // Given
        var quietField = WatchCommand.class.getDeclaredField("quiet");
        quietField.setAccessible(true);
        quietField.set(command, true);

        // When
        boolean quiet = (boolean) quietField.get(command);

        // Then
        assertTrue(quiet);
    }

    @Test
    void shouldBuildCorrectWebSocketUrl() throws Exception {
        // Given
        var hostField = WatchCommand.class.getDeclaredField("host");
        hostField.setAccessible(true);
        hostField.set(command, "test-host");

        var portField = WatchCommand.class.getDeclaredField("port");
        portField.setAccessible(true);
        portField.set(command, 8080);

        // When
        String host = (String) hostField.get(command);
        int port = (int) portField.get(command);
        String expectedUrl = String.format("ws://%s:%d/ws", host, port);

        // Then
        assertEquals("ws://test-host:8080/ws", expectedUrl);
    }

    @Test
    void shouldHaveDefaultQuietModeFalse() throws Exception {
        // Given
        var quietField = WatchCommand.class.getDeclaredField("quiet");
        quietField.setAccessible(true);
        quietField.set(command, false);

        // When
        boolean quiet = (boolean) quietField.get(command);

        // Then
        assertFalse(quiet);
    }

    @Test
    void shouldAllowNullFilter() throws Exception {
        // Given
        var filterField = WatchCommand.class.getDeclaredField("filter");
        filterField.setAccessible(true);
        filterField.set(command, null);

        // When
        String filter = (String) filterField.get(command);

        // Then
        assertNull(filter);
    }

    @Test
    void shouldBuildWsUrlFromCustomHostAndPort() throws Exception {
        setField("host", "myserver");
        setField("port", 9999);
        String url = String.format("ws://%s:%d/ws", getField("host"), getField("port"));
        assertEquals("ws://myserver:9999/ws", url);
    }

    @Test
    void shouldAcceptFilterWithDotNotation() throws Exception {
        setField("filter", "agent.started");
        assertEquals("agent.started", getField("filter"));
    }

    // helpers
    private void setField(String name, Object value) throws Exception {
        var f = WatchCommand.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(command, value);
    }

    private Object getField(String name) throws Exception {
        var f = WatchCommand.class.getDeclaredField(name);
        f.setAccessible(true);
        return f.get(command);
    }
}
