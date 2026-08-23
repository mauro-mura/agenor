package dev.agenor.core.mailbox;

import dev.agenor.core.Message;
import dev.agenor.core.MessageHandler;
import dev.agenor.core.messaging.Subscription;

/**
 * The single inbound path of one agent.
 *
 * <p>An agent's mailbox owns the only persistent subscription to that agent's recipient
 * channel. Everything addressed to the agent is handed to {@link #offer(Message)} and is
 * claimed from the queue by a single consumer, which decides where it goes. Components that
 * previously subscribed on their own behalf — annotated message handlers and the dialogue
 * layer — become consumers of that single path instead of competing subscribers.
 *
 * <p>The queue is a hand-off buffer between the delivery thread and the consumer, not a
 * store: everything claimed is routed immediately, nothing is retained awaiting a later
 * request. There is deliberately no pull operation.
 *
 * <p>Messages are <em>claimed</em> in arrival order. Handler invocation is not serialised,
 * so claim order is not completion order and an agent must not assume its handlers run one
 * at a time.
 *
 * <p>A mailbox is local to the process holding the agent and keeps nothing across a restart.
 * Where the transport delivers at-least-once, the same message may be offered more than
 * once: a mailbox does not deduplicate.
 *
 * @since 0.27.0
 */
public interface AgentMailbox {

    /**
     * Hands a message to this mailbox.
     *
     * <p>When the mailbox is full, the configured {@link OverflowPolicy} decides the
     * outcome. This method does not block.
     *
     * @param message the message to enqueue; must not be {@code null}
     * @return {@code true} if the message was accepted, {@code false} if it was dropped
     * @throws NullPointerException                                  if {@code message} is
     *                                                               {@code null}
     * @throws dev.agenor.core.exceptions.MailboxOverflowException   if the mailbox is full
     *                                                               and the policy is
     *                                                               {@link OverflowPolicy#REJECT}
     */
    boolean offer(Message message);

    /**
     * Returns the number of messages currently queued.
     *
     * @return a value between zero and {@link #capacity()}
     */
    int size();

    /**
     * Returns the maximum number of messages this mailbox holds before its overflow policy
     * applies.
     *
     * @return a positive capacity
     */
    int capacity();

    /**
     * Registers the consumer for messages that carry a known performative.
     *
     * <p>The mailbox routes what it claims down one of two lanes: this consumer when the
     * message is dialogue traffic, and the agent's own message handling otherwise. That split
     * is the reason a mailbox exists, so it belongs here rather than on any one
     * implementation. At most one dialogue consumer is registered at a time.
     *
     * <p>With no consumer registered — an agent that does not speak the dialogue protocol —
     * every message goes to the agent's own handling, so the lane simply stays unused.
     *
     * @param handler invoked for each claimed dialogue message; must not be {@code null}
     * @return a handle whose {@code unsubscribe()} withdraws the consumer, after which
     *         dialogue traffic falls to the agent's own handling
     * @throws NullPointerException if {@code handler} is {@code null}
     */
    Subscription registerDialogueConsumer(MessageHandler handler);

    /**
     * Opens the mailbox so that offered messages are claimed and routed.
     *
     * <p>Idempotent: starting an already-started mailbox does nothing.
     */
    void start();

    /**
     * Closes the mailbox, discarding anything still queued.
     *
     * <p>Returns once the mailbox has stopped claiming, so a stopped agent leaves nothing
     * running behind it. Idempotent: stopping an already-stopped mailbox does nothing.
     */
    void stop();
}
