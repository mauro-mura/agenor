package dev.agenor.runtime.agent;

import dev.agenor.core.guardrail.GuardrailContext;
import dev.agenor.core.guardrail.GuardrailViolationException;
import dev.agenor.core.llm.LLMMemoryAware;
import dev.agenor.core.llm.LLMMessage;
import dev.agenor.core.memory.MemoryEntry;
import dev.agenor.core.memory.llm.ContextWindowStrategy;
import dev.agenor.core.memory.llm.LLMMemoryManager;
import dev.agenor.core.telemetry.JenticTelemetry;
import dev.agenor.runtime.guardrail.GuardrailChain;
import dev.agenor.runtime.memory.llm.ContextWindowStrategies;
import dev.agenor.core.llm.LLMProvider;
import dev.agenor.core.reflection.CritiqueResult;
import dev.agenor.core.reflection.ReflectionConfig;
import dev.agenor.core.reflection.ReflectionStrategy;
import dev.agenor.runtime.reflection.DefaultReflectionStrategy;
import dev.agenor.runtime.telemetry.InstrumentedLLMProvider;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * Base class for LLM-powered agents that use language model memory.
 *
 * <p>
 * Extends {@link BaseAgent} with convenient methods for:
 * <ul>
 * <li>Managing conversation history</li>
 * <li>Building LLM prompts with context</li>
 * <li>Storing and retrieving facts</li>
 * <li>Auto-summarization of long conversations</li>
 * </ul>
 *
 * <p>
 * <b>Prerequisites:</b> The runtime must inject an
 * {@link dev.agenor.core.memory.llm.LLMMemoryManager} via
 * {@link #setLLMMemoryManager} before the agent starts.
 *
 * <p>
 * <b>Example Usage:</b>
 *
 * <pre>{@code
 * @Agent("chat-bot")
 * public class ChatBot extends LLMAgent {
 *
 * 	@AgenorMessageHandler("user.message")
 * 	public void handleUserMessage(Message msg) {
 * 		String userInput = msg.getContent(String.class);
 *
 * 		// Add user message to conversation
 * 		addConversationMessage(LLMMessage.user(userInput)).join();
 *
 * 		// Build prompt with conversation + context
 * 		List<LLMMessage> prompt = buildLLMPrompt(userInput, 2000).join();
 *
 * 		// Call LLM with prompt
 * 		String response = callLLM(prompt);
 *
 * 		// Add response to conversation
 * 		addConversationMessage(LLMMessage.assistant(response)).join();
 *
 * 		// Send response back
 * 		sendMessage(Message.builder().topic("bot.response").content(response).build());
 * 	}
 * }
 * }</pre>
 *
 * @since 0.6.0
 */
public abstract class LLMAgent extends BaseAgent implements LLMMemoryAware {

	/**
	 * LLM Memory support (injected by runtime)
	 */
	private LLMMemoryManager llmMemoryManager;

	/**
	 * Default context window strategy (SLIDING).
	 */
	protected ContextWindowStrategy defaultStrategy = ContextWindowStrategies.SLIDING;

	/**
	 * Default conversation token budget.
	 */
	protected int defaultConversationBudget = 2000;

	/**
	 * Default context token budget.
	 */
	protected int defaultContextBudget = 500;

	/**
	 * Auto-summarization threshold (tokens). When conversation exceeds this, oldest
	 * messages are auto-summarized.
	 */
	protected int autoSummarizeThreshold = 5000;

	/**
	 * Messages to summarize when threshold is reached.
	 */
	protected int messagesToSummarize = 10;

	/**
	 * Optional LLM provider registered by subclasses to enable automatic creation
	 * of {@link DefaultReflectionStrategy} when no explicit
	 * {@link ReflectionStrategy} is configured.
	 *
	 * <p>
	 * Subclasses call {@link #setLLMProvider} in their constructor or
	 * {@code onStart()} to opt in to the auto-default behavior.
	 *
	 * @since 0.12.0
	 */
	private LLMProvider llmProvider;

	/**
	 * Optional reflection strategy. When {@code null}, {@link #reflect} creates a
	 * {@link DefaultReflectionStrategy} backed by {@link #llmProvider} if set.
	 *
	 * @since 0.12.0
	 */
	private ReflectionStrategy reflectionStrategy;

	/**
	 * Optional guardrail chain applied pre-input and post-output. {@code null}
	 * means no guardrails are active (backward-compatible default).
	 *
	 * @since 0.13.0
	 */
	private GuardrailChain guardrailChain;

	/**
	 * Creates an LLM agent with auto-generated ID.
	 */
	protected LLMAgent() {
		super();
	}

	/**
	 * Creates an LLM agent with specific ID.
	 *
	 * @param agentId the agent identifier
	 */
	protected LLMAgent(String agentId) {
		super(agentId);
	}

	/**
	 * Creates an LLM agent with ID and name.
	 *
	 * @param agentId   the agent identifier
	 * @param agentName the agent display name
	 */
	protected LLMAgent(String agentId, String agentName) {
		super(agentId, agentName);
	}

	// ========== LLM MEMORY MANAGER ==========

	/**
	 * Injects the LLM memory manager (optional).
	 *
	 * @param llmMemoryManager
	 */
	@Override
	public void setLLMMemoryManager(LLMMemoryManager llmMemoryManager) {
		this.llmMemoryManager = llmMemoryManager;
		log.debug("LLM memory manager configured for agent: {}", getAgentId());
	}

	/**
	 * accessor with validation
	 *
	 * @return llmMemoryManager
	 */
	protected LLMMemoryManager getLLMMemoryManager() {
		if (llmMemoryManager == null) {
			throw new IllegalStateException("LLMMemoryManager not initialized");
		}
		return llmMemoryManager;
	}

	/**
	 * check llm memory availability
	 *
	 * @return true if LLM memory is configured
	 */
	protected boolean hasLLMMemory() {
		return llmMemoryManager != null;
	}

	// -------------------------------------------------------------------------
	// Reflection — provider registration
	// -------------------------------------------------------------------------

	/**
	 * Registers the {@link LLMProvider} used by this agent.
	 *
	 * <p>
	 * Subclasses call this method to enable the automatic creation of a
	 * {@link DefaultReflectionStrategy} when {@link #reflect} is invoked without an
	 * explicit strategy configured.
	 *
	 * <pre>{@code
	 * public MyAgent() {
	 *     this.provider = LLMProviderFactory.openai().apiKey(...).build();
	 *     setLLMProvider(provider);  // opt in to auto-default reflection
	 * }
	 * }</pre>
	 *
	 * @param provider the LLM provider (non-null)
	 *
	 * @since 0.12.0
	 */
	protected void setLLMProvider(LLMProvider provider) {
		this.llmProvider = Objects.requireNonNull(provider, "provider must not be null");
	}

	// -------------------------------------------------------------------------
	// Reflection — strategy injection
	// -------------------------------------------------------------------------

	/**
	 * Sets a custom {@link ReflectionStrategy} for this agent.
	 *
	 * <p>
	 * When set, {@link #reflect} uses this strategy instead of creating a
	 * {@link DefaultReflectionStrategy} automatically.
	 *
	 * @param strategy the strategy to use, or {@code null} to revert to
	 *                 auto-default
	 *
	 * @since 0.12.0
	 */
	public void setReflectionStrategy(ReflectionStrategy strategy) {
		this.reflectionStrategy = strategy;
	}

	/**
	 * Returns the currently configured {@link ReflectionStrategy}, or {@code null}
	 * if none has been set explicitly.
	 *
	 * @return the reflection strategy, or {@code null}
	 *
	 * @since 0.12.0
	 */
	public ReflectionStrategy getReflectionStrategy() {
		return reflectionStrategy;
	}

	/**
	 * Injects the {@link GuardrailChain} that will be executed around every LLM
	 * call.
	 *
	 * <p>
	 * Called by {@code JenticRuntime} when an agent is annotated with
	 * {@code @WithGuardrails}, or can be called directly for programmatic
	 * configuration. Pass {@code null} to remove an existing chain.
	 *
	 * @param chain the chain to inject, or {@code null} to disable guardrails
	 * @since 0.13.0
	 */
	public void setGuardrailChain(GuardrailChain chain) {
		this.guardrailChain = chain;
	}

	/**
	 * Returns the currently configured {@link GuardrailChain}, or {@code null} if
	 * none.
	 *
	 * @return the guardrail chain, or {@code null}
	 * @since 0.13.0
	 */
	public GuardrailChain getGuardrailChain() {
		return guardrailChain;
	}

	/**
	 * Installs telemetry instrumentation on this agent.
	 *
	 * <p>Wraps the current {@link LLMProvider} (if set) with an
	 * {@link InstrumentedLLMProvider} and propagates telemetry to the
	 * {@link GuardrailChain} (if present). Called by {@code JenticRuntime}
	 * at agent registration time.
	 *
	 * @param telemetry the telemetry instance; {@code null} is silently ignored
	 * @since 0.19.0
	 */
	public void installTelemetry(JenticTelemetry telemetry) {
		if (telemetry == null) {
			return;
		}
		if (llmProvider != null && !(llmProvider instanceof InstrumentedLLMProvider)) {
			llmProvider = new InstrumentedLLMProvider(llmProvider, telemetry);
		}
		if (guardrailChain != null) {
			guardrailChain.setTelemetry(telemetry);
		}
	}

	// -------------------------------------------------------------------------
	// Reflection — convenience entry point
	// -------------------------------------------------------------------------

	/**
	 * Critiques {@code output} against {@code task} using the configured
	 * {@link ReflectionStrategy}.
	 *
	 * <p>
	 * Resolution order:
	 * <ol>
	 * <li>If {@link #setReflectionStrategy} was called → use that strategy.</li>
	 * <li>Else if {@link #setLLMProvider} was called → create a
	 * {@link DefaultReflectionStrategy} backed by that provider.</li>
	 * <li>Else → throw {@link IllegalStateException}.</li>
	 * </ol>
	 *
	 * <p>
	 * Example:
	 *
	 * <pre>{@code
	 * String output = callLLM(prompt);
	 * CritiqueResult critique = reflect(output, task, ReflectionConfig.defaults()).join();
	 * if (critique.shouldRevise()) {
	 * 	output = callLLM(buildRevisionPrompt(output, critique.feedback()));
	 * }
	 * }</pre>
	 *
	 * @param output the LLM-generated text to evaluate (non-null)
	 * @param task   the original task description (non-null)
	 * @param config reflection parameters (non-null)
	 * @return future resolving to a {@link CritiqueResult}
	 * @throws IllegalStateException if neither {@code reflectionStrategy} nor
	 *                               {@code llmProvider} has been configured
	 *
	 * @since 0.12.0
	 */
	protected CompletableFuture<CritiqueResult> reflect(String output, String task, ReflectionConfig config) {
		ReflectionStrategy effective = resolveStrategy();
		return effective.critique(output, task, config);
	}

	/**
	 * Critiques {@code output} using {@link ReflectionConfig#defaults()}.
	 *
	 * @param output the LLM-generated text to evaluate (non-null)
	 * @param task   the original task description (non-null)
	 * @return future resolving to a {@link CritiqueResult}
	 * @throws IllegalStateException if neither strategy nor provider is configured
	 *
	 * @since 0.12.0
	 */
	protected CompletableFuture<CritiqueResult> reflect(String output, String task) {
		return reflect(output, task, ReflectionConfig.defaults());
	}

	/**
	 * Returns {@code true} if reflection is available (strategy or provider set).
	 *
	 * @return {@code true} if {@link #reflect} can be called without throwing
	 *
	 * @since 0.12.0
	 */
	protected boolean hasReflection() {
		return reflectionStrategy != null || llmProvider != null;
	}

	private ReflectionStrategy resolveStrategy() {
		if (reflectionStrategy != null) {
			return reflectionStrategy;
		}
		if (llmProvider != null) {
			return new DefaultReflectionStrategy(llmProvider);
		}
		throw new IllegalStateException("Reflection is not configured. Call setReflectionStrategy() or "
				+ "setLLMProvider() before invoking reflect().");
	}

	// ========== BASE LLM MEMORY OPERATIONS ==========

	/**
	 * Adds a message to the LLM conversation history.
	 *
	 * <p>
	 * This is a direct delegation to
	 * {@link dev.agenor.core.memory.llm.LLMMemoryManager#addMessage}. For enhanced
	 * functionality with auto-summarization, see
	 * {@link LLMAgent#addConversationMessage}.
	 *
	 * @param message the LLM message to add
	 * @return a future that completes when the message is added
	 * @throws IllegalStateException if LLM memory manager not configured
	 * @since 0.6.0
	 * @see LLMAgent#addConversationMessage for enhanced version
	 */
	protected CompletableFuture<Void> addLLMMessage(dev.agenor.core.llm.LLMMessage message) {
		return getLLMMemoryManager().addMessage(message);
	}

	/**
	 * Adds multiple messages to the LLM conversation history.
	 *
	 * <p>
	 * This is a direct delegation to
	 * {@link dev.agenor.core.memory.llm.LLMMemoryManager#addMessages}.
	 *
	 * @param messages the messages to add
	 * @return a future that completes when messages are added
	 * @throws IllegalStateException if LLM memory manager not configured
	 * @since 0.6.0
	 */
	protected CompletableFuture<Void> addLLMMessages(List<dev.agenor.core.llm.LLMMessage> messages) {
		return getLLMMemoryManager().addMessages(messages);
	}

	/**
	 * Gets the LLM conversation history within token budget.
	 *
	 * <p>
	 * This is a direct delegation to
	 * {@link dev.agenor.core.memory.llm.LLMMemoryManager#getConversationHistory}.
	 *
	 * @param maxTokens maximum number of tokens
	 * @param strategy  the context window strategy to use
	 * @return a future with the conversation history
	 * @throws IllegalStateException if LLM memory manager not configured
	 * @since 0.6.0
	 */
	protected CompletableFuture<List<dev.agenor.core.llm.LLMMessage>> getLLMConversationHistory(int maxTokens,
                                                                                                dev.agenor.core.memory.llm.ContextWindowStrategy strategy) {
		return getLLMMemoryManager().getConversationHistory(maxTokens, strategy);
	}

	/**
	 * Stores a fact in long-term LLM memory.
	 *
	 * <p>
	 * This is a direct delegation to
	 * {@link dev.agenor.core.memory.llm.LLMMemoryManager#remember}. For simplified
	 * API with defaults, see {@link LLMAgent#storeFact}.
	 *
	 * @param key      the fact key
	 * @param content  the fact content
	 * @param metadata the metadata
	 * @return a future that completes when stored
	 * @throws IllegalStateException if LLM memory manager not configured
	 * @since 0.6.0
	 * @see LLMAgent#storeFact for simplified version
	 */
	protected CompletableFuture<Void> rememberLLM(String key, String content, Map<String, Object> metadata) {
		return getLLMMemoryManager().remember(key, content, metadata);
	}

	/**
	 * Retrieves relevant context from long-term LLM memory.
	 *
	 * <p>
	 * This is a direct delegation to
	 * {@link dev.agenor.core.memory.llm.LLMMemoryManager#retrieveRelevantContext}.
	 *
	 * @param query     the search query
	 * @param maxTokens maximum tokens for results
	 * @return a future with relevant memory entries
	 * @throws IllegalStateException if LLM memory manager not configured
	 * @since 0.6.0
	 */
	protected CompletableFuture<List<MemoryEntry>> retrieveLLMContext(String query, int maxTokens) {
		return getLLMMemoryManager().retrieveRelevantContext(query, maxTokens);
	}

	/**
	 * Clears the LLM conversation history.
	 *
	 * <p>
	 * This clears short-term conversation but preserves long-term facts.
	 *
	 * @return a future that completes when cleared
	 * @throws IllegalStateException if LLM memory manager not configured
	 * @since 0.6.0
	 */
	protected CompletableFuture<Void> clearLLMConversation() {
		return getLLMMemoryManager().clearConversationHistory();
	}

	/**
	 * Gets the current token count of the conversation.
	 *
	 * @return the current token count
	 * @throws IllegalStateException if LLM memory manager not configured
	 * @since 0.6.0
	 */
	protected int getLLMConversationTokens() {
		return getLLMMemoryManager().getCurrentTokenCount();
	}

	/**
	 * Gets the number of messages in the conversation.
	 *
	 * @return the message count
	 * @throws IllegalStateException if LLM memory manager not configured
	 * @since 0.6.0
	 */
	protected int getLLMConversationMessageCount() {
		return getLLMMemoryManager().getMessageCount();
	}

	// ========== CONVERSATION MANAGEMENT ==========

	/**
	 * Add a message to the conversation history.
	 *
	 * <p>
	 * Enhanced version of {@link #addLLMMessage} with auto-summarization.
	 *
	 * @param message the LLM message
	 * @return future that completes when added
	 */
	protected CompletableFuture<Void> addConversationMessage(LLMMessage message) {
		return addLLMMessage(message).thenRun(() -> checkAndSummarizeIfNeeded());
	}

	/**
	 * Add multiple messages to the conversation history.
	 *
	 * <p>
	 * Enhanced version of {@link #addLLMMessages} with auto-summarization.
	 *
	 * @param messages the messages
	 * @return future that completes when added
	 */
	protected CompletableFuture<Void> addConversationMessages(List<LLMMessage> messages) {
		return addLLMMessages(messages).thenRun(() -> checkAndSummarizeIfNeeded());
	}

	/**
	 * Get conversation history with default budget and strategy.
	 *
	 * @return future with conversation messages
	 */
	protected CompletableFuture<List<LLMMessage>> getConversation() {
		return getConversation(defaultConversationBudget, defaultStrategy);
	}

	/**
	 * Get conversation history with custom budget and strategy.
	 *
	 * @param maxTokens maximum tokens
	 * @param strategy  selection strategy
	 * @return future with conversation messages
	 */
	protected CompletableFuture<List<LLMMessage>> getConversation(int maxTokens, ContextWindowStrategy strategy) {
		return getLLMConversationHistory(maxTokens, strategy);
	}

	/**
	 * Clear conversation history (keeps long-term facts).
	 *
	 * @return future that completes when cleared
	 */
	protected CompletableFuture<Void> clearConversation() {
		return clearLLMConversation();
	}

	/**
	 * Get current conversation token count.
	 *
	 * @return token count
	 */
	public int getConversationTokens() {
		return getLLMConversationTokens();
	}

	/**
	 * Get conversation message count.
	 *
	 * @return message count
	 */
	public int getConversationMessageCount() {
		return getLLMConversationMessageCount();
	}

	// ========== FACTS / LONG-TERM MEMORY ==========

	/**
	 * Store a fact in long-term memory.
	 *
	 * <p>
	 * Simplified version of {@link #rememberLLM} with empty metadata.
	 *
	 * @param key     the fact key
	 * @param content the fact content
	 * @return future that completes when stored
	 */
	protected CompletableFuture<Void> storeFact(String key, String content) {
		return storeFact(key, content, Map.of());
	}

	/**
	 * Store a fact in long-term memory with metadata.
	 *
	 * <p>
	 * Convenience wrapper around {@link #rememberLLM}.
	 *
	 * @param key      the fact key
	 * @param content  the fact content
	 * @param metadata the metadata
	 * @return future that completes when stored
	 */
	protected CompletableFuture<Void> storeFact(String key, String content, Map<String, Object> metadata) {
		return rememberLLM(key, content, metadata);
	}

	/**
	 * Retrieve relevant facts from long-term memory.
	 *
	 * @param query the search query
	 * @return future with relevant facts
	 */
	protected CompletableFuture<List<MemoryEntry>> retrieveFacts(String query) {
		return retrieveFacts(query, defaultContextBudget);
	}

	/**
	 * Retrieve relevant facts with custom token budget.
	 *
	 * @param query     the search query
	 * @param maxTokens maximum tokens
	 * @return future with relevant facts
	 */
	protected CompletableFuture<List<MemoryEntry>> retrieveFacts(String query, int maxTokens) {
		return retrieveLLMContext(query, maxTokens);
	}

	// ========== PROMPT BUILDING ==========

	/**
	 * Build a complete LLM prompt with conversation and context.
	 *
	 * <p>
	 * The prompt includes:
	 * <ul>
	 * <li>System message with relevant facts (if any)</li>
	 * <li>Conversation history (within budget)</li>
	 * <li>New user message</li>
	 * </ul>
	 *
	 * @param userMessage the new user message
	 * @param totalBudget total token budget for prompt
	 * @return future with complete prompt
	 */
	protected CompletableFuture<List<LLMMessage>> buildLLMPrompt(String userMessage, int totalBudget) {
		return buildLLMPrompt(userMessage, totalBudget, null);
	}

	/**
	 * Build a complete LLM prompt with conversation and context.
	 *
	 * @param userMessage  the new user message
	 * @param totalBudget  total token budget for prompt
	 * @param contextQuery query for retrieving relevant facts (null for no context)
	 * @return future with complete prompt
	 */
	protected CompletableFuture<List<LLMMessage>> buildLLMPrompt(String userMessage, int totalBudget,
			String contextQuery) {
		return CompletableFuture.supplyAsync(() -> {
			List<LLMMessage> prompt = new ArrayList<>();

			int conversationBudget = totalBudget;

			// Retrieve context if query provided
			if (contextQuery != null && !contextQuery.trim().isEmpty()) {
				List<MemoryEntry> context = retrieveFacts(contextQuery, defaultContextBudget).join();

				if (!context.isEmpty()) {
					String contextStr = context.stream().map(entry -> {
						String key = (String) entry.metadata().getOrDefault("key", "fact");
						return key + ": " + entry.content();
					}).collect(Collectors.joining(", "));

					prompt.add(LLMMessage.system("Relevant facts: " + contextStr));

					// Reduce conversation budget by context tokens
					int contextTokens = getLLMMemoryManager().getTokenEstimator().estimateTokens(prompt.get(0));
					conversationBudget -= contextTokens;
				}
			}

			// Add conversation history
			List<LLMMessage> history = getConversation(conversationBudget, defaultStrategy).join();
			prompt.addAll(history);

			// Add new user message
			prompt.add(LLMMessage.user(userMessage));

			return prompt;
		});
	}

	// ========== AUTO-SUMMARIZATION ==========

	/**
	 * Check if conversation needs summarization and do it if needed.
	 */
	private void checkAndSummarizeIfNeeded() {
		if (autoSummarizeThreshold > 0 && getConversationTokens() > autoSummarizeThreshold) {
			log.info("Conversation tokens ({}) exceeded threshold ({}), auto-summarizing...", getConversationTokens(),
					autoSummarizeThreshold);

			getLLMMemoryManager().summarizeOldMessages(messagesToSummarize).thenAccept(summary -> {
				log.info("Auto-summarized {} messages: {}", messagesToSummarize, summary);
			}).exceptionally(e -> {
				log.error("Failed to auto-summarize conversation", e);
				return null;
			});
		}
	}

	/**
	 * Manually trigger summarization of old messages.
	 *
	 * @param count number of oldest messages to summarize
	 * @return future with summary text
	 */
	protected CompletableFuture<String> summarizeConversation(int count) {
		return getLLMMemoryManager().summarizeOldMessages(count);
	}

	// ========== CONFIGURATION ==========

	/**
	 * Set the default context window strategy.
	 *
	 * @param strategy the strategy
	 */
	protected void setDefaultStrategy(ContextWindowStrategy strategy) {
		this.defaultStrategy = strategy;
	}

	/**
	 * Set the default conversation token budget.
	 *
	 * @param budget the budget
	 */
	protected void setDefaultConversationBudget(int budget) {
		this.defaultConversationBudget = budget;
	}

	/**
	 * Set the default context token budget.
	 *
	 * @param budget the budget
	 */
	protected void setDefaultContextBudget(int budget) {
		this.defaultContextBudget = budget;
	}

	/**
	 * Configure auto-summarization.
	 *
	 * @param threshold           token threshold (0 to disable)
	 * @param messagesToSummarize number of messages to summarize
	 */
	protected void configureAutoSummarization(int threshold, int messagesToSummarize) {
		this.autoSummarizeThreshold = threshold;
		this.messagesToSummarize = messagesToSummarize;
	}

	/**
	 * Disable auto-summarization.
	 */
	protected void disableAutoSummarization() {
		this.autoSummarizeThreshold = 0;
	}

	// -------------------------------------------------------------------------
	// Guardrail hooks
	// -------------------------------------------------------------------------

	/**
	 * Runs all input guardrails against {@code input}.
	 *
	 * <p>
	 * If no guardrail chain is configured, returns {@code input} unchanged. If a
	 * guardrail modifies the input, the modified value is returned. If a guardrail
	 * blocks the input, {@link GuardrailViolationException} is thrown and the LLM
	 * is <em>not</em> called.
	 *
	 * @param input the raw user input
	 * @param ctx   guardrail context for this invocation
	 * @return the (possibly modified) input after passing all input guardrails
	 * @throws GuardrailViolationException if any guardrail blocks the input
	 * @since 0.13.0
	 */
	protected String applyInputGuardrails(String input, GuardrailContext ctx) throws GuardrailViolationException {
		if (guardrailChain == null || guardrailChain.hasNoInputGuardrails()) {
			return input;
		}
		return guardrailChain.applyInput(input, ctx);
	}

	/**
	 * Runs all output guardrails against {@code output}.
	 *
	 * <p>
	 * If no guardrail chain is configured, returns {@code output} unchanged. If a
	 * guardrail modifies the output, the modified value is returned to the
	 * consumer. If a guardrail blocks the output,
	 * {@link GuardrailViolationException} is thrown.
	 *
	 * @param output the raw LLM output
	 * @param ctx    guardrail context for this invocation
	 * @return the (possibly modified) output after passing all output guardrails
	 * @throws GuardrailViolationException if any guardrail blocks the output
	 * @since 0.13.0
	 */
	protected String applyOutputGuardrails(String output, GuardrailContext ctx) throws GuardrailViolationException {
		if (guardrailChain == null || guardrailChain.hasNoOutputGuardrails()) {
			return output;
		}
		return guardrailChain.applyOutput(output, ctx);
	}
}
