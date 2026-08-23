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

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
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
 * call is dispatched onto a virtual thread, as message delivery already worked before this
 * class existed. Running handlers on the drain thread would give end-to-end ordering at the
 * price of letting one slow handler stall the agent's whole queue.
 *
 * @since 0.27.0
 */
public final class DefaultAgentMailbox implements AgentMailbox {

    private static final Logger log = LoggerFactory.getLogger(DefaultAgentMailbox.class);

    private final String agentId;
    private final MailboxConfig config;
    private final BlockingQueue<Message> queue;
    private final MessageHandler pushConsumer;
    private final AtomicReference<MessageHandler> dialogueConsumer = new AtomicReference<>();

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
    }

    /**
     * Starts the drain. Calling this on a started mailbox does nothing.
     */
    public synchronized void start() {
        if (drain != null) {
            return;
        }
        drain = Thread.ofVirtual()
                .name("mailbox-drain-" + agentId)
                .start(this::drainLoop);
        log.debug("Mailbox started for agent '{}' (capacity {}, overflow {})",
                agentId, config.capacity(), config.overflowPolicy());
    }

    /**
     * Stops the drain and discards anything still queued.
     *
     * <p>Returns once the drain thread has finished, so a stopped agent leaves no thread
     * behind. Calling this on a stopped mailbox does nothing.
     */
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
            toStop.join(java.time.Duration.ofSeconds(5));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        int discarded = queue.size();
        queue.clear();
        if (discarded > 0) {
            log.warn("Mailbox for agent '{}' stopped with {} message(s) still queued",
                    agentId, discarded);
        }
        log.debug("Mailbox stopped for agent '{}'", agentId);
    }

    @Override
    public boolean offer(Message message) {
        Objects.requireNonNull(message, "message");
        synchronized (queue) {
            if (queue.offer(message)) {
                return true;
            }
            return switch (config.overflowPolicy()) {
                case DROP_OLDEST -> {
                    Message dropped = queue.poll();
                    log.warn("Mailbox for agent '{}' is full (capacity {}): dropped oldest message {}",
                            agentId, config.capacity(), dropped == null ? "<none>" : dropped.id());
                    yield queue.offer(message);
                }
                case DROP_NEWEST -> {
                    log.warn("Mailbox for agent '{}' is full (capacity {}): dropped incoming message {}",
                            agentId, config.capacity(), message.id());
                    yield false;
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
     * Registers the consumer for dialogue messages. At most one is registered at a time;
     * registering a second replaces the first.
     *
     * @param handler invoked for every claimed message carrying a known performative;
     *                must not be null
     * @return a subscription whose {@code unsubscribe()} deregisters this consumer, after
     *         which dialogue messages fall to the push consumer
     * @throws NullPointerException if {@code handler} is null
     */
    public Subscription registerDialogueConsumer(MessageHandler handler) {
        Objects.requireNonNull(handler, "handler");
        dialogueConsumer.set(handler);
        var id = "mailbox-dialogue-" + agentId + "-" + UUID.randomUUID();
        return Subscription.of(id, () -> dialogueConsumer.compareAndSet(handler, null));
    }

    private void drainLoop() {
        try {
            while (!Thread.currentThread().isInterrupted()) {
                route(queue.take());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        log.debug("Mailbox drain finished for agent '{}'", agentId);
    }

    private void route(Message message) {
        var dialogue = dialogueConsumer.get();
        var handler = (dialogue != null && DialogueMessage.isDialogueMessage(message))
                ? dialogue
                : pushConsumer;
        Thread.startVirtualThread(() -> {
            try {
                handler.handle(message).join();
            } catch (Exception e) {
                log.error("Handler failed for message {} on agent '{}': {}",
                        message.id(), agentId, e.getMessage(), e);
            }
        });
    }
}
