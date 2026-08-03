package dev.agenor.runtime.memory.llm;

import dev.agenor.core.memory.MemoryStore;
import dev.agenor.core.memory.llm.LLMMemoryManager;
import dev.agenor.core.spi.DefaultLLMMemoryManagerProvider;

/**
 * Default {@link DefaultLLMMemoryManagerProvider}, discovered via
 * {@link java.util.ServiceLoader}.
 *
 * @since 0.25.0
 */
public final class DefaultLLMMemoryManagerFactory implements DefaultLLMMemoryManagerProvider {

    @Override
    public LLMMemoryManager create(MemoryStore memoryStore, String agentId) {
        return new DefaultLLMMemoryManager(memoryStore, new SimpleTokenEstimator(), agentId);
    }
}
