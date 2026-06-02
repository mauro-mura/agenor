# ADR-010: LLM Memory Management System

**Status**: Accepted  
**Date**: 2025-12-23  
**Updated**: 2026-03-06 — Added `LLMMemoryAware` interface; injection now targets `LLMMemoryAware` instead of `LLMAgent` directly (see `CHANGELOG.md`)
**Authors**: Project Team

---

## Context

Multi-agent systems that interact with Large Language Models (LLMs) face several memory management challenges:

1. **Conversation History**: LLMs require conversation context to maintain coherent dialogues, but API context windows are limited (typically 4k-128k tokens)

2. **Token Management**: Each message consumes tokens; exceeding limits causes API failures or high costs

3. **Long-term Memory**: Facts learned during conversations need persistent storage beyond the current session

4. **Context Window Strategies**: Different strategies needed for managing limited context (sliding window, fixed window, summarization)

5. **Multi-agent Coordination**: Each agent needs isolated conversation history while sharing long-term knowledge

6. **Performance**: Memory operations must be fast to avoid blocking agent responses

### Existing System

Basic `MemoryStore` with generic key-value storage:
- `rememberShort()` / `rememberLong()` - simple text storage
- `recall()` / `recallAll()` - basic retrieval
- No LLM-specific features (tokens, messages, conversations)

This was insufficient for LLM agents requiring:
- Structured conversation history (user/assistant messages)
- Token counting and budget management
- Context window strategies
- Semantic search for relevant context

---

## Decision

Implement a comprehensive **LLM Memory Management System** layered on top of the Week 1 MemoryStore.

### Architecture Overview

```
┌─────────────────────────────────────────┐
│         LLMAgent (Enhanced)             │
│  - addConversationMessage()             │
│  - buildLLMPrompt()                     │
│  - storeFact()                          │
│  - Auto-summarization                   │
└─────────────┬───────────────────────────┘
              │
┌─────────────▼───────────────────────────┐
│      LLMMemoryManager (Core)            │
│  - Conversation History (short-term)    │
│  - Fact Storage (long-term)             │
│  - Token Management                     │
│  - Context Window Strategies            │
└─────────────┬───────────────────────────┘
              │
┌─────────────▼───────────────────────────┐
│         MemoryStore                     │
│  - Generic key-value storage            │
│  - Search capabilities                  │
└─────────────────────────────────────────┘
```

### Core Components

#### 1. LLMMemoryManager Interface

```java
public interface LLMMemoryManager {
    // Conversation management (short-term)
    CompletableFuture<Void> addMessage(LLMMessage message);
    CompletableFuture<List<LLMMessage>> getConversationHistory(
        int maxTokens, 
        ContextWindowStrategy strategy
    );
    CompletableFuture<Void> clearConversationHistory();
    
    // Fact storage (long-term)
    CompletableFuture<Void> remember(String key, String content, Map<String, Object> metadata);
    CompletableFuture<List<MemoryEntry>> retrieveRelevantContext(String query, int maxTokens);
    
    // Token management
    int getCurrentTokenCount();
    int getMessageCount();
}
```

**Key Design Decisions**:
- Async API (`CompletableFuture`) for non-blocking operations
- Separation of short-term (conversation) and long-term (facts)
- Token-based budgeting for all operations
- Strategy pattern for context window management

#### 2. LLMMessage Structure

```java
public record LLMMessage(
    Role role,              // USER, ASSISTANT, SYSTEM
    String content,         // Message text
    int tokenCount,         // Estimated tokens
    Instant timestamp,      // When created
    Map<String, Object> metadata  // Extensible metadata
) {
    public enum Role { USER, ASSISTANT, SYSTEM }
}
```

**Rationale**:
- Immutable record for thread safety
- Pre-computed token count for efficiency
- Metadata for extensibility (tool calls, citations, etc.)
- Timestamp for conversation ordering

#### 3. TokenEstimator Interface

```java
public interface TokenEstimator {
    int estimateTokens(String text);
    int estimateTokens(LLMMessage message);
    int estimateTokens(List<LLMMessage> messages);
}
```

**Implementation**: `SimpleTokenEstimator`
- Word-based approximation: `tokens ≈ words * 1.3`
- Configurable per-message overhead (4 tokens for role markers)
- Fast, no external dependencies
- Sufficient accuracy for budgeting (±10%)

**Alternatives Considered**:
- TikToken (OpenAI's tokenizer): Accurate but adds dependency
- Character-based: `tokens ≈ chars / 4` - less accurate
- **Decision**: Simple estimator by default, interface allows custom implementations

#### 4. ContextWindowStrategy Interface

```java
public interface ContextWindowStrategy {
    List<LLMMessage> selectMessages(
        List<LLMMessage> messages,
        int maxTokens,
        TokenEstimator estimator
    );
}
```

**Implementations**:

**a) SlidingWindowStrategy** (default)
```
[msg1, msg2, msg3, msg4, msg5, msg6]
         └─────────┬──────────┘
           Selected (fits budget)
```
- Selects most recent messages that fit budget
- Natural conversation flow
- Good for ongoing dialogues

**b) FixedWindowStrategy**
```
[msg1, msg2, msg3, msg4, msg5, msg6]
 └──┬───┘                  └──┬───┘
  System                  Recent
```
- Always includes system messages + recent
- Preserves instructions
- Good for task-oriented bots

**c) SummarizedWindowStrategy**
```
[msg1, msg2, msg3, msg4, msg5, msg6]
 └──────┬──────┘         └──┬───┘
   Summary            Recent (full)
```
- Summarizes old messages
- Keeps recent messages full
- Requires LLM for summarization
- Best for very long conversations

#### 5. DefaultLLMMemoryManager Implementation

**Storage Structure**:
```java
// Short-term (in-memory)
private List<LLMMessage> conversationHistory = new CopyOnWriteArrayList<>();

// Long-term (via MemoryStore)
memoryStore.store(MemoryEntry.builder()
    .key("fact:" + key)
    .content(content)
    .scope(LONG_TERM)
    .ownerId(agentId)
    .metadata(metadata)
    .build()
);
```

**Key Features**:
- Conversation in-memory for speed (CopyOnWriteArrayList for thread-safety)
- Facts persisted to MemoryStore for durability
- Per-agent isolation via agentId
- Token tracking for budget enforcement

### Integration with Agents

#### Location Decision: LLMAgent (not BaseAgent)

**Rationale**:
- **Separation of Concerns**: BaseAgent = essentials, LLMAgent = LLM features
- **No Pollution**: Non-LLM agents (TimerAgent, etc.) don't get unused methods
- **Encapsulation**: Private field in LLMAgent (better than protected in BaseAgent)
- **Semantic Clarity**: If you need LLM → extend `LLMAgent`. If you already extend a domain
  superclass and cannot extend `LLMAgent`, implement `LLMMemoryAware` directly.

**LLMAgent Structure**:
```java
public abstract class LLMAgent extends BaseAgent {
    // LLM Memory Manager
    private LLMMemoryManager llmMemoryManager;
    
    // Base operations (delegate to manager)
    protected CompletableFuture<Void> addLLMMessage(LLMMessage msg);
    protected CompletableFuture<List<LLMMessage>> getLLMConversationHistory(...);
    // ... 6 more base methods
    
    // Enhanced operations (with auto-summarization, defaults, etc.)
    protected CompletableFuture<Void> addConversationMessage(LLMMessage msg);
    protected CompletableFuture<List<LLMMessage>> buildLLMPrompt(String query, int budget);
    protected CompletableFuture<Void> storeFact(String key, String content);
    // ... more convenience methods
    
    // Configuration
    protected int defaultConversationBudget = 2000;
    protected int autoSummarizeThreshold = 5000;
}
```

### Auto-Injection by Runtime

**Pattern**: Factory-based automatic injection

```java
public class JenticRuntime {
    private Function<String, LLMMemoryManager> llmMemoryManagerFactory;

    public void registerAgent(Agent agent) {
        // Inject into any agent implementing LLMMemoryAware,
        // regardless of whether it extends BaseAgent or LLMAgent.
        if (agent instanceof LLMMemoryAware llmAware
                && memoryStore != null
                && llmMemoryManagerFactory != null) {
            LLMMemoryManager llm = llmMemoryManagerFactory.apply(agent.getAgentId());
            llmAware.setLLMMemoryManager(llm);
        }
    }
}
```

**Benefits**:
- 90% code reduction (3 lines vs 20+)
- Impossible to forget configuration
- Correct agentId guaranteed
- Selective injection (any `LLMMemoryAware` implementor, not only `LLMAgent` subclasses)

**Factory Pattern**:
```java
runtime.builder()
    .memoryStore(store)
    .llmMemoryManagerFactory(agentId -> 
        new CustomLLMMemoryManager(store, estimator, agentId)
    )
    .build();
```

Allows custom implementations while providing sensible defaults.

---

## Consequences

### Positive

1. **Structured Conversation Management** ✅
   - Type-safe message handling (LLMMessage vs String)
   - Automatic token tracking
   - Thread-safe conversation history

2. **Token Budget Control** ✅
   - Prevents API errors from exceeding limits
   - Predictable costs (token counting before API calls)
   - Multiple strategies for different use cases

3. **Long-term Memory** ✅
   - Facts persist beyond conversations
   - Semantic search for relevant context
   - Isolated per agent

4. **Performance** ✅
   - In-memory conversation (fast)
   - Async operations (non-blocking)
   - CopyOnWriteArrayList (thread-safe reads)

5. **Flexibility** ✅
   - Pluggable strategies (context window)
   - Pluggable estimators (token counting)
   - Factory pattern for custom implementations

6. **Clean Architecture** ✅
   - Clear separation BaseAgent / LLMAgent
   - Interface-based design
   - No pollution of base classes

7. **Developer Experience** ✅
   - Auto-injection (90% less boilerplate)
   - Simple API for common cases
   - Powerful API for advanced cases

### Negative

1. **Memory Overhead** ⚠️
   - Each LLMAgent: ~10 KB (conversation history + manager)
   - Mitigated by: Selective injection (only LLMAgent gets it)
   - For 100 agents (10 LLM, 90 non-LLM): only 100 KB

2. **Token Estimation Accuracy** ⚠️
   - SimpleTokenEstimator: ±10% accuracy
   - Mitigated by: Interface allows TikToken integration
   - Impact: Minor (budget safety margins handle variance)

3. **Conversation Loss on Restart** ⚠️
   - In-memory history not persisted
   - Mitigated by: MemoryStore for facts (what matters)
   - Future: Optional conversation persistence

4. **Learning Curve** ⚠️
   - New concepts (LLMMessage, strategies, tokens)
   - Mitigated by: Extensive documentation, working examples

### Trade-offs

#### Memory: In-Memory vs Persistent

**Decision**: Hybrid approach
- Conversation: In-memory (speed, typical conversations < 1 KB)
- Facts: Persistent (MemoryStore)

**Rationale**: 
- Conversations are transient (session-specific)
- Facts are durable (learned knowledge)
- Performance critical for conversation access
- Persistence critical for facts

#### Token Estimation: Accuracy vs Speed

**Decision**: Fast approximation (SimpleTokenEstimator)

**Rationale**:
- Speed: <1ms per message
- Accuracy: ±10% sufficient for budgeting
- Extensibility: Interface allows accurate estimators
- Dependencies: Zero external deps by default

#### Auto-Injection: Automatic vs Manual

**Decision**: Automatic with factory pattern

**Rationale**:
- DX: 90% less boilerplate
- Safety: Can't forget configuration
- Flexibility: Factory allows customization
- Overhead: Negligible (~1ms per agent)

---

## Implementation Details

### Key Classes

```
dev.agenor.core.memory.llm/
├── LLMMemoryManager.java          (interface)
├── TokenEstimator.java            (interface)
├── ContextWindowStrategy.java     (interface)
└── MemoryQuery.java               (search query)

dev.agenor.core.llm/
├── LLMMessage.java                (message record)
└── LLMMemoryAware.java            (injection marker interface)

dev.agenor.runtime.memory.llm/
├── DefaultLLMMemoryManager.java   (implementation)
├── SimpleTokenEstimator.java      (implementation)
└── ContextWindowStrategies.java   (strategy implementations)

dev.agenor.runtime.agent/
├── BaseAgent.java                 (clean, no LLM)
└── LLMAgent.java                  (complete LLM features)

dev.agenor.runtime/
└── JenticRuntime.java             (auto-injection)
```

### Example Usage

**Simple Case** (auto-injection):
```java
// Setup (3 lines)
JenticRuntime runtime = JenticRuntime.builder()
    .memoryStore(new InMemoryStore())
    .build();

// Agent (LLMMemoryManager auto-injected)
@JenticAgent("chat-bot")
public class ChatBot extends LLMAgent {
    
    @JenticMessageHandler("user.message")
    public void handleMessage(Message msg) {
        String input = msg.getContent(String.class);
        
        // Add to conversation
        addConversationMessage(LLMMessage.user(input)).join();
        
        // Build prompt (conversation + context)
        List<LLMMessage> prompt = buildLLMPrompt(input, 2000).join();
        
        // Call LLM
        String response = callLLM(prompt);
        
        // Add response
        addConversationMessage(LLMMessage.assistant(response)).join();
        
        // Store fact
        storeFact("user-name", extractName(input)).join();
    }
}
```

**Advanced Case** (custom factory):
```java
TokenEstimator tikToken = new TikTokenEstimator();

JenticRuntime runtime = JenticRuntime.builder()
    .memoryStore(new InMemoryStore())
    .llmMemoryManagerFactory(agentId ->
        new DefaultLLMMemoryManager(
            memoryStore,
            tikToken,  // Custom estimator
            agentId
        )
    )
    .build();
```

---

## Migration Path

### Adoption Strategy

1. **Existing Agents**: No changes required
2. **New LLM Agents**: Extend LLMAgent instead of BaseAgent
3. **Custom Memory**: Implement LLMMemoryManager interface
4. **Custom Tokens**: Implement TokenEstimator interface
