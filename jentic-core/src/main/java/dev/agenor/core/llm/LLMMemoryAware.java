package dev.agenor.core.llm;

import dev.agenor.core.memory.llm.LLMMemoryManager;

/**
 * Marks an agent as a receiver of an injected {@link LLMMemoryManager}.
 *
 * <p>The runtime detects this interface during agent registration and injects
 * a per-agent {@link LLMMemoryManager} instance automatically, regardless of
 * whether the agent extends {@code BaseAgent} or
 * implements {@link dev.agenor.core.Agent} directly.
 *
 * <p>Agents that extend {@code LLMAgent} already implement this interface
 * and require no changes. Plain {@code Agent} implementors that need LLM
 * memory can implement {@code LLMMemoryAware} directly:
 *
 * <pre>{@code
 * @Agent("my-domain-agent")
 * public class MyDomainAgent extends DomainClass implements Agent, LLMMemoryAware {
 *
 *     private LLMMemoryManager llmMemoryManager;
 *
 *     public MyDomainAgent(AgentContext ctx) { ... }
 *
 *     @Override
 *     public void setLLMMemoryManager(LLMMemoryManager manager) {
 *         this.llmMemoryManager = manager;
 *     }
 * }
 * }</pre>
 *
 * @since 0.10.0
 * @see LLMMemoryManager
 */
public interface LLMMemoryAware {

    /**
     * Injects the LLM memory manager for this agent.
     *
     * <p>Called by the runtime during agent registration when a
     * {@link dev.agenor.core.memory.MemoryStore} is configured.
     * Implementations should store the reference for later use in
     * message handlers or behaviors.
     *
     * @param manager the LLM memory manager instance, never null
     */
    void setLLMMemoryManager(LLMMemoryManager manager);
}
