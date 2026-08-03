package dev.agenor.core.spi;

import dev.agenor.core.memory.MemoryStore;
import dev.agenor.core.memory.llm.LLMMemoryManager;

/**
 * Supplies the default {@link LLMMemoryManager} implementation backing
 * {@link dev.agenor.core.llm.LLMMemoryAware} agents, discovered via
 * {@link java.util.ServiceLoader}.
 *
 * <p>{@code agenor-runtime} depends on this interface only; the implementation
 * lives in {@code agenor-runtime-llm}. When absent, {@code AgenorRuntime} skips
 * injecting a default {@code LLMMemoryManager} (agents that implement
 * {@code LLMMemoryAware} directly with their own manager are unaffected).
 *
 * @since 0.25.0
 */
public interface DefaultLLMMemoryManagerProvider {

    /**
     * Creates a new {@link LLMMemoryManager} for one agent.
     *
     * @param memoryStore the backing memory store; never {@code null}
     * @param agentId     the owning agent's ID; never {@code null}
     * @return a new memory manager instance; never {@code null}
     */
    LLMMemoryManager create(MemoryStore memoryStore, String agentId);
}
