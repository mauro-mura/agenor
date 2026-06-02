package dev.agenor.core.console;

import org.junit.jupiter.api.Test;
import java.util.concurrent.CompletableFuture;
import static org.junit.jupiter.api.Assertions.*;

class WebConsoleTest {

    @Test
    void getBaseUrlShouldReturnCorrectFormat() {
        WebConsole console = new TestWebConsole(8080);

        assertEquals("http://localhost:8080", console.getBaseUrl());
    }

    @Test
    void getBaseUrlShouldWorkWithDifferentPorts() {
        WebConsole console1 = new TestWebConsole(9090);
        WebConsole console2 = new TestWebConsole(3000);

        assertEquals("http://localhost:9090", console1.getBaseUrl());
        assertEquals("http://localhost:3000", console2.getBaseUrl());
    }

    @Test
    void getApiUrlShouldAppendApiPath() {
        WebConsole console = new TestWebConsole(8080);

        assertEquals("http://localhost:8080/api", console.getApiUrl());
    }

    @Test
    void getApiUrlShouldWorkWithDifferentPorts() {
        WebConsole console1 = new TestWebConsole(9090);
        WebConsole console2 = new TestWebConsole(3000);

        assertEquals("http://localhost:9090/api", console1.getApiUrl());
        assertEquals("http://localhost:3000/api", console2.getApiUrl());
    }

    @Test
    void getWebSocketUrlShouldReturnCorrectFormat() {
        WebConsole console = new TestWebConsole(8080);

        assertEquals("ws://localhost:8080/ws", console.getWebSocketUrl());
    }

    @Test
    void getWebSocketUrlShouldWorkWithDifferentPorts() {
        WebConsole console1 = new TestWebConsole(9090);
        WebConsole console2 = new TestWebConsole(3000);

        assertEquals("ws://localhost:9090/ws", console1.getWebSocketUrl());
        assertEquals("ws://localhost:3000/ws", console2.getWebSocketUrl());
    }

    @Test
    void allDefaultMethodsShouldWorkTogether() {
        WebConsole console = new TestWebConsole(8888);

        assertEquals("http://localhost:8888", console.getBaseUrl());
        assertEquals("http://localhost:8888/api", console.getApiUrl());
        assertEquals("ws://localhost:8888/ws", console.getWebSocketUrl());
        assertEquals(8888, console.getPort());
    }

    private static class TestWebConsole implements WebConsole {
        private final int port;

        TestWebConsole(int port) {
            this.port = port;
        }

        @Override
        public CompletableFuture<Void> start() {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<Void> stop() {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public boolean isRunning() {
            return false;
        }

        @Override
        public int getPort() {
            return port;
        }
    }
}
