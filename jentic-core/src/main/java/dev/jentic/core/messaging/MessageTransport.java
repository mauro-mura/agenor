package dev.jentic.core.messaging;

import dev.jentic.core.Message;
import dev.jentic.core.MessageHandler;
import dev.jentic.core.TransportEndpoint;

import java.util.concurrent.CompletableFuture;

/**
 * Low-level transport primitive used by the message dispatcher — not by agents directly.
 *
 * <p>Each distributed backend (Redis, Kafka, …) provides a {@code MessageTransport}
 * implementation. The dispatcher calls {@link #send} after resolving the target agent's
 * {@link dev.jentic.core.AgentEndpoint} to a {@link TransportEndpoint}. Local
 * in-memory delivery short-circuits this layer entirely.
 *
 * @since 0.20.0
 * @see MessageDispatcher
 * @see TransportEndpoint
 */
public interface MessageTransport {

    /**
     * Sends a message to the given transport endpoint.
     *
     * @param dest the physical destination resolved by the dispatcher, must not be null
     * @param msg  the message to send, must not be null
     * @return a future that completes when the message has been handed off to the transport
     * @throws NullPointerException if dest or msg is null
     */
    CompletableFuture<Void> send(TransportEndpoint dest, Message msg);

    /**
     * Registers a handler for messages arriving at the given local transport endpoint.
     *
     * @param local   the local endpoint to listen on, must not be null
     * @param handler the handler to invoke on each received message, must not be null
     * @return a {@link Subscription} that can be used to stop listening
     * @throws NullPointerException if local or handler is null
     */
    Subscription subscribe(TransportEndpoint local, MessageHandler handler);
}
