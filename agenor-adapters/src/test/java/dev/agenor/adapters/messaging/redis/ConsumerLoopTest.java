package dev.agenor.adapters.messaging.redis;

import io.lettuce.core.RedisCommandExecutionException;
import io.lettuce.core.XReadArgs;
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
                msg -> CompletableFuture.completedFuture(null), () -> { }, streamClient, config);
        return loop;
    }

    /** Runs a loop whose first read throws {@code error} and whose later reads return nothing. */
    @SuppressWarnings("unchecked")
    private void runThroughOneFailedRead(RuntimeException error, Runnable recreateGroup,
                                         int readsToWaitFor) throws Exception {
        var reads = new java.util.concurrent.CountDownLatch(readsToWaitFor);
        var first = new java.util.concurrent.atomic.AtomicBoolean(true);
        when(streamClient.newConsumerConnection()).thenReturn(mockConn);
        when(mockConn.sync()).thenReturn(mockCmds);
        when(mockCmds.xreadgroup(any(), any(), any())).thenAnswer(inv -> {
            // The read of this consumer's own pending entries at start is not the loop's read
            // under test: it finds nothing, and a failure there is left to the reclaim.
            XReadArgs.StreamOffset<String> offset = inv.getArgument(2);
            if (!">".equals(offset.getOffset())) return List.of();
            reads.countDown();
            if (first.compareAndSet(true, false)) throw error;
            Thread.sleep(5);
            return List.of();
        });

        loop = new ConsumerLoop("agenor:node:n", "cg", "consumer-1",
                msg -> CompletableFuture.completedFuture(null), recreateGroup, streamClient, config);
        loop.start();
        org.junit.jupiter.api.Assertions.assertTrue(
                reads.await(5, java.util.concurrent.TimeUnit.SECONDS), "loop did not read again");
        loop.stop();
    }

    @Test
    @DisplayName("NOGROUP recreates the group, once, and the loop reads again without waiting")
    void noGroup_recreatesTheGroup() throws Exception {
        Runnable recreateGroup = org.mockito.Mockito.mock(Runnable.class);

        runThroughOneFailedRead(new RedisCommandExecutionException(
                "NOGROUP No such key 'k' or consumer group 'cg' in XREADGROUP with GROUP option"),
                recreateGroup, 3);

        verify(recreateGroup).run();
    }

    @Test
    @DisplayName("an error that is not NOGROUP leaves the group alone")
    void otherError_doesNotRecreateTheGroup() throws Exception {
        Runnable recreateGroup = org.mockito.Mockito.mock(Runnable.class);

        runThroughOneFailedRead(new RedisCommandExecutionException("LOADING server is loading"),
                recreateGroup, 2);

        verify(recreateGroup, org.mockito.Mockito.never()).run();
    }

    @Test
    @DisplayName("a group that cannot be recreated yet is tried again on the next failed read")
    @SuppressWarnings("unchecked")
    void noGroup_recreationFailure_isRetried() throws Exception {
        var recreated = new java.util.concurrent.CountDownLatch(1);
        var attempts = new java.util.concurrent.atomic.AtomicInteger();
        var groupBack = new java.util.concurrent.atomic.AtomicBoolean(false);
        when(streamClient.newConsumerConnection()).thenReturn(mockConn);
        when(mockConn.sync()).thenReturn(mockCmds);
        when(mockCmds.xreadgroup(any(), any(), any())).thenAnswer(inv -> {
            if (!groupBack.get()) throw new RedisCommandExecutionException("NOGROUP gone");
            Thread.sleep(5);
            return List.of();
        });

        loop = new ConsumerLoop("agenor:node:n", "cg", "consumer-1",
                msg -> CompletableFuture.completedFuture(null), () -> {
                    if (attempts.incrementAndGet() == 1) throw new IllegalStateException("still reconnecting");
                    groupBack.set(true);
                    recreated.countDown();
                }, streamClient, config);
        loop.start();

        org.junit.jupiter.api.Assertions.assertTrue(
                recreated.await(5, java.util.concurrent.TimeUnit.SECONDS), "group never recreated");
        org.assertj.core.api.Assertions.assertThat(attempts.get()).isEqualTo(2);
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
