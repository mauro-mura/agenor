package dev.agenor.runtime.mailbox;

import dev.agenor.core.Message;
import dev.agenor.core.MessageHandler;
import dev.agenor.core.dialogue.DialogueMessage;
import dev.agenor.core.exceptions.MailboxOverflowException;
import dev.agenor.core.mailbox.AgentMailbox;
import dev.agenor.core.mailbox.MailboxConfig;
import dev.agenor.core.messaging.Subscription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The default {@link AgentMailbox}: a bounded queue drained by one consumer thread.
 *
 * <p>The drain claims messages one at a time, in arrival order, and routes each one:
 * a message carrying a known performative goes to the dialogue consumer when one is
 * registered, everything else goes to the push consumer that serves annotated handlers and
 * {@code onDirectMessage}. Routing decides the same way whatever transport delivered the
 * message.
 *
 * <p>Claiming is serialised; handler invocation is not. Once a message is routed the handler
 * call is dispatched onto a virtual thread, so a slow handler cannot stall the agent's whole
 * queue. The number of handlers running at once is bounded, and the drain blocks while that
 * bound is reached — that, not the queue, is where backpressure actually comes from
 * (ADR-033 D-2).
 *
 * <p>Each offered message carries a future that the mailbox completes when its handler
 * completes. The transport that delivered the message can therefore acknowledge after
 * processing rather than at enqueue, which is what keeps redelivery and dead-lettering
 * working through the mailbox (ADR-033 D-1).
 *
 * @since 0.27.0
 */
public final class DefaultAgentMailbox implements AgentMailbox {

    private static final Logger log = LoggerFactory.getLogger(DefaultAgentMailbox.class);

    /** How long {@link #stop()} waits for handlers already running. */
    private static final Duration QUIESCE_TIMEOUT = Duration.ofSeconds(5);

    /** One queued message and the promise made to whoever offered it. */
    private record Envelope(Message message, CompletableFuture<Void> outcome) { }

    private final String agentId;
    private final MailboxConfig config;
    private final BlockingQueue<Envelope> queue;
    private final MessageHandler pushConsumer;
    private final AtomicReference<MessageHandler> dialogueConsumer = new AtomicReference<>();
    private final Semaphore inFlight;

    private volatile Thread drain;

    /**
     * Creates a mailbox for one agent.
     *
     * @param agentId      identifier of the owning agent; must not be null or blank
     * @param config       bounds and overflow behaviour; must not be null
     * @param pushConsumer receives every message that is not routed to the dialogue
     *                     consumer; must not be null
     * @throws NullPointerException     if any argument is null
     * @throws IllegalArgumentException if {@code agentId} is blank
     */
    public DefaultAgentMailbox(String agentId, MailboxConfig config, MessageHandler pushConsumer) {
        this.agentId = Objects.requireNonNull(agentId, "agentId");
        this.config = Objects.requireNonNull(config, "config");
        this.pushConsumer = Objects.requireNonNull(pushConsumer, "pushConsumer");
        if (agentId.isBlank()) {
            throw new IllegalArgumentException("agentId must not be blank");
        }
        this.queue = new ArrayBlockingQueue<>(config.capacity());
        this.inFlight = new Semaphore(config.maxConcurrentHandlers());
    }

    @Override
    public synchronized void start() {
        if (drain != null) {
            return;
        }
        drain = Thread.ofVirtual()
                .name("mailbox-drain-" + agentId)
                .start(this::drainLoop);
        log.debug("Mailbox started for agent '{}' (capacity {}, overflow {}, max handlers {})",
                agentId, config.capacity(), config.overflowPolicy(), config.maxConcurrentHandlers());
    }

    @Override
    public void stop() {
        Thread toStop;
        synchronized (this) {
            toStop = drain;
            drain = null;
        }
        if (toStop == null) {
            return;
        }
        toStop.interrupt();
        try {
            toStop.join(QUIESCE_TIMEOUT);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        awaitQuiescence();
        drainRemaining();
        log.debug("Mailbox stopped for agent '{}'", agentId);
    }

    /**
     * Waits for handlers already running to finish. Acquiring every permit is only possible
     * once none is held, which is precisely the condition "nothing in flight".
     */
    private void awaitQuiescence() {
        int permits = config.maxConcurrentHandlers();
        try {
            if (inFlight.tryAcquire(permits, QUIESCE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                inFlight.release(permits);
            } else {
                log.warn("Mailbox for agent '{}' stopped with handlers still running after {}",
                        agentId, QUIESCE_TIMEOUT);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Fails whatever is still queued. Completing these exceptionally is what stops a transport
     * that acknowledges on completion from acknowledging work this mailbox never did.
     */
    private void drainRemaining() {
        List<Envelope> discarded = new ArrayList<>();
        queue.drainTo(discarded);
        if (discarded.isEmpty()) {
            return;
        }
        var reason = new IllegalStateException(
                "Mailbox for agent '" + agentId + "' stopped before this message was handled");
        discarded.forEach(e -> e.outcome().completeExceptionally(reason));
        log.warn("Mailbox for agent '{}' stopped with {} message(s) still queued; "
                        + "they were not acknowledged", agentId, discarded.size());
    }

    @Override
    public CompletableFuture<Void> offer(Message message) {
        Objects.requireNonNull(message, "message");
        var envelope = new Envelope(message, new CompletableFuture<>());

        synchronized (queue) {
            if (queue.offer(envelope)) {
                return envelope.outcome();
            }
            // Retry before evicting: a concurrent take() between the failed offer and the poll
            // would otherwise discard a message that did not need discarding (ADR-033 D-4).
            if (queue.offer(envelope)) {
                return envelope.outcome();
            }
            return switch (config.overflowPolicy()) {
                case DROP_OLDEST -> {
                    Envelope dropped = queue.poll();
                    if (dropped != null) {
                        dropped.outcome().completeExceptionally(
                                new MailboxOverflowException(agentId, config.capacity()));
                        log.warn("Mailbox for agent '{}' is full (capacity {}): dropped oldest "
                                        + "message {}, which stays unacknowledged",
                                agentId, config.capacity(), dropped.message().id());
                    }
                    if (!queue.offer(envelope)) {
                        envelope.outcome().completeExceptionally(
                                new MailboxOverflowException(agentId, config.capacity()));
                    }
                    yield envelope.outcome();
                }
                case DROP_NEWEST -> {
                    log.warn("Mailbox for agent '{}' is full (capacity {}): dropped incoming "
                                    + "message {}, which stays unacknowledged",
                            agentId, config.capacity(), message.id());
                    envelope.outcome().completeExceptionally(
                            new MailboxOverflowException(agentId, config.capacity()));
                    yield envelope.outcome();
                }
                case REJECT -> throw new MailboxOverflowException(agentId, config.capacity());
            };
        }
    }

    @Override
    public int size() {
        return queue.size();
    }

    @Override
    public int capacity() {
        return config.capacity();
    }

    /**
     * {@inheritDoc}
     *
     * <p>Registering a second consumer replaces the first.
     */
    @Override
    public Subscription registerDialogueConsumer(MessageHandler handler) {
        Objects.requireNonNull(handler, "handler");
        dialogueConsumer.set(handler);
        var id = "mailbox-dialogue-" + agentId + "-" + UUID.randomUUID();
        return Subscription.of(id, () -> dialogueConsumer.compareAndSet(handler, null));
    }

    private void drainLoop() {
        try {
            while (!Thread.currentThread().isInterrupted()) {
                var envelope = queue.take();
                // Blocks once the agent is saturated. This is the backpressure: the queue only
                // starts filling after handler concurrency is exhausted.
                inFlight.acquire();
                route(envelope);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        log.debug("Mailbox drain finished for agent '{}'", agentId);
    }

    private void route(Envelope envelope) {
        var message = envelope.message();
        var dialogue = dialogueConsumer.get();
        var handler = (dialogue != null && DialogueMessage.isDialogueMessage(message))
                ? dialogue
                : pushConsumer;
        Thread.startVirtualThread(() -> {
            try {
                handler.handle(message).join();
                envelope.outcome().complete(null);
            } catch (Exception e) {
                log.error("Handler failed for message {} on agent '{}': {}",
                        message.id(), agentId, e.getMessage(), e);
                envelope.outcome().completeExceptionally(e);
            } finally {
                inFlight.release();
            }
        });
    }
}
