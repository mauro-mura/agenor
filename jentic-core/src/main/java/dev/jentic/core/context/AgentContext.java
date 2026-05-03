package dev.jentic.core.context;

import dev.jentic.core.AgentDirectory;
import dev.jentic.core.BehaviorScheduler;
import dev.jentic.core.MessageService;
import dev.jentic.core.memory.MemoryStore;
import dev.jentic.core.messaging.MessageDispatcher;

/**
 * Aggregates the core services available to every agent.
 *
 * <p>Agents that cannot extend {@code BaseAgent} (e.g. because they already
 * have a domain superclass) can declare an {@code AgentContext} constructor
 * parameter and receive all framework services in a single injection point:
 *
 * <pre>{@code
 * @JenticAgent("order-processor")
 * public class OrderProcessorAgent extends DomainEntity implements Agent {
 *
 *     private final AgentContext ctx;
 *
 *     public OrderProcessorAgent(AgentContext ctx) {
 *         this.ctx = ctx;
 *     }
 *
 *     @JenticMessageHandler("order.created")
 *     public void handle(Message msg) {
 *         ctx.messageDispatcher().publish("orders.confirmed", msg);
 *     }
 * }
 * }</pre>
 *
 * <p>{@code memoryStore} may be {@code null} when the runtime is configured
 * without a {@code MemoryStore}.
 *
 * @param messageService    the messaging service, never null
 * @param agentDirectory    the agent discovery service, never null
 * @param behaviorScheduler the behavior scheduler, never null
 * @param memoryStore       the memory store, may be null
 *
 * @since 0.10.0
 */
public record AgentContext(
        MessageService messageService,
        AgentDirectory agentDirectory,
        BehaviorScheduler behaviorScheduler,
        MemoryStore memoryStore
) {

    /**
     * Compact canonical constructor — validates required services.
     */
    public AgentContext {
        if (messageService == null) throw new IllegalArgumentException("messageService must not be null");
        if (agentDirectory == null) throw new IllegalArgumentException("agentDirectory must not be null");
        if (behaviorScheduler == null) throw new IllegalArgumentException("behaviorScheduler must not be null");
    }

    /**
     * Convenience constructor for runtimes without a {@link MemoryStore}.
     *
     * @param messageService    the messaging service, never null
     * @param agentDirectory    the agent discovery service, never null
     * @param behaviorScheduler the behavior scheduler, never null
     */
    public AgentContext(MessageService messageService, AgentDirectory agentDirectory,
                        BehaviorScheduler behaviorScheduler) {
        this(messageService, agentDirectory, behaviorScheduler, null);
    }

    /**
     * Returns the {@link MessageDispatcher} view of the message service.
     *
     * @since 0.20.0
     */
    public MessageDispatcher messageDispatcher() {
        return messageService;
    }
}