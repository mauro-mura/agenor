package dev.agenor.core.messaging;

import dev.agenor.core.AgentEndpoint;

import java.util.Optional;

/**
 * Opt-in extension point for a {@link MessageDispatcher} that knows how agents on its own node
 * are reachable from other nodes.
 *
 * <p>A {@link dev.agenor.core.AgentDescriptor} carries an {@link AgentEndpoint} so that a
 * dispatcher can route {@code sendTo(...)} without a second directory lookup. The problem this
 * interface solves is that nothing in the registration path knows what that endpoint should say:
 * the runtime builds the descriptor, but only the transport knows its own node identity and how
 * it is addressed. A dispatcher that can answer implements this; one that cannot says nothing.
 *
 * <p>This is deliberately <strong>not</strong> part of {@link MessageDispatcher}, for the same
 * reason {@link dev.agenor.core.LifecycleHooks} is not part of {@code Agent}: an in-JVM
 * dispatcher has no node identity worth publishing and should not be forced to invent one.
 *
 * <p><strong>Why this matters — the failure it prevents.</strong> Before this interface, a
 * runtime combining a persistent directory with a networked transport advertised no endpoint at
 * all: the JDBC registry stored an empty {@code node_id}, and the Redis dispatcher then routed
 * cross-node messages to a node stream that no consumer was reading. Delivery failed
 * <em>silently</em> — no exception, no warning, the message simply never arrived. An
 * implementation of this interface must therefore return an endpoint its own
 * {@link MessageDispatcher} can actually deliver to, or return {@link Optional#empty()}: a wrong
 * answer here is worse than no answer, because no answer preserves the single-node behaviour.
 *
 * <p>Implementations must be safe to call at agent-registration time, before the runtime has
 * started.
 *
 * @since 0.26.0
 * @see AgentEndpoint
 * @see MessageDispatcher
 */
public interface LocalEndpointProvider {

    /**
     * Returns the endpoint under which agents registered on this node should be advertised.
     *
     * <p>The runtime stamps the returned value onto each agent's descriptor before registering
     * it in the directory, so a peer resolving that agent learns which node owns it and which
     * transport reaches it.
     *
     * @return the endpoint to advertise, or {@link Optional#empty()} if this dispatcher has no
     *         node identity to publish — in which case the directory keeps whatever default it
     *         applies for local agents
     */
    Optional<AgentEndpoint> localEndpoint();
}
