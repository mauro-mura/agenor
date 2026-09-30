package dev.agenor.adapters.messaging.redis;

import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The lifecycle of {@link ConsumerLoop}: what {@code start()} does when the connection cannot
 * be opened, and what {@code stop()} guarantees about the thread and the connection.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ConsumerLoop lifecycle")
class ConsumerLoopTest {

    @Mock
    private RedisStreamClient streamClient;
    @Mock
    @SuppressWarnings("rawtypes")
    private StatefulRedisConnection mockConn;
    @Mock
    @SuppressWarnings("rawtypes")
    private RedisCommands mockCmds;

    private final RedisMessagingConfig config = new RedisMessagingConfig(
            "redis://localhost", "node-1", "agenor", 100, 1000, 30_000, 3);

    private ConsumerLoop loop;

    @AfterEach
    void tearDown() {
        if (loop != null) loop.stop();
    }

    private ConsumerLoop newLoop() {
        loop = new ConsumerLoop("agenor:topic:t", "cg", "consumer-1",
                msg -> CompletableFuture.completedFuture(null), streamClient, config);
        return loop;
    }

    @Test
    @DisplayName("a connection that cannot be opened fails start() instead of leaving a silent loop")
    @SuppressWarnings("unchecked")
    void start_propagatesConnectionFailure_andCanBeRetried() {
        when(streamClient.newConsumerConnection())
                .thenThrow(new IllegalStateException("connection refused"))
                .thenReturn(mockConn);
        lenient().when(mockConn.sync()).thenReturn(mockCmds);
        lenient().when(mockCmds.xreadgroup(any(), any(), any())).thenReturn(List.of());

        var consumerLoop = newLoop();

        assertThatThrownBy(consumerLoop::start)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("connection refused");

        // Had `running` stayed true, this would be a no-op and the loop would never connect.
        consumerLoop.start();
        consumerLoop.stop();
        verify(streamClient, org.mockito.Mockito.times(2)).newConsumerConnection();
    }

    @Test
    @DisplayName("stop() returns only after the loop thread has closed its connection")
    @SuppressWarnings("unchecked")
    void stop_waitsForTheLoopToCloseItsConnection() throws Exception {
        var inRead = new java.util.concurrent.CountDownLatch(1);
        when(streamClient.newConsumerConnection()).thenReturn(mockConn);
        when(mockConn.sync()).thenReturn(mockCmds);
        // A read that outlasts the interrupt, as a blocking XREADGROUP does.
        when(mockCmds.xreadgroup(any(), any(), any())).thenAnswer(inv -> {
            inRead.countDown();
            // Ignores interrupts for the whole 150 ms on purpose: stop() must still wait for it.
            long until = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(150);
            while (System.nanoTime() < until) {
                try {
                    Thread.sleep(10);
                } catch (InterruptedException ignored) {
                    // keep waiting
                }
            }
            return List.of();
        });

        var consumerLoop = newLoop();
        consumerLoop.start();
        org.junit.jupiter.api.Assertions.assertTrue(
                inRead.await(2, java.util.concurrent.TimeUnit.SECONDS), "loop never reached its read");

        consumerLoop.stop();

        verify(mockConn).close();
    }
}
