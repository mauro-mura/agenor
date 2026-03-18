package dev.jentic.adapters.mcp;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;

import static org.junit.jupiter.api.Assertions.*;

class McpClientFactoryTest {

    @Test
    void factory_isNotInstantiable() throws Exception {
        Constructor<McpClientFactory> ctor =
                McpClientFactory.class.getDeclaredConstructor();
        ctor.setAccessible(true);
        InvocationTargetException ex = assertThrows(InvocationTargetException.class,
                ctor::newInstance,
                "McpClientFactory must not be instantiable");
        assertInstanceOf(UnsupportedOperationException.class, ex.getCause());
    }

    @Test
    void fromSse_withUnreachableUrl_throwsDuringInitialize() {
        // initialize() handshake fails against a non-existing server
        assertThrows(Exception.class, () ->
                McpClientFactory.fromSse("http://localhost:19999/sse"));
    }

    @Test
    void fromStdio_withInvalidCommand_throwsDuringInitialize() {
        // ProcessBuilder starts but initialize() fails — or the process itself cannot start
        assertThrows(Exception.class, () ->
                McpClientFactory.fromStdio("this-command-does-not-exist-xyz"));
    }
}