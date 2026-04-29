package dev.jentic.examples.agent;

import dev.jentic.core.Agent;
import dev.jentic.core.Message;
import dev.jentic.core.annotations.JenticAgent;
import dev.jentic.core.annotations.JenticMessageHandler;
import dev.jentic.core.llm.LLMMessage;
import dev.jentic.core.memory.MemoryEntry;
import dev.jentic.core.memory.MemoryQuery;
import dev.jentic.core.memory.MemoryScope;
import dev.jentic.runtime.agent.LLMAgent;
import dev.jentic.runtime.memory.llm.ContextWindowStrategies;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * Example chat agent that uses LLM memory for conversations.
 *
 * <p>This agent demonstrates:
 * <ul>
 *   <li>Conversation history management</li>
 *   <li>Long-term fact storage</li>
 *   <li>Context-aware responses</li>
 *   <li>Auto-summarization</li>
 * </ul>
 *
 * <p><b>Example Usage:</b>
 * <pre>{@code
 * // Create and configure the agent
 * ChatAgent agent = new ChatAgent();
 * agent.setMemoryStore(new InMemoryStore());
 * agent.setLLMMemoryManager(new DefaultLLMMemoryManager(...));
 * agent.setMessageService(messageService);
 *
 * // Start agent
 * agent.start().join();
 *
 * // Send messages
 * messageService.send(Message.builder()
 *     .topic("user.message")
 *     .content("Hi, my name is Alice")
 *     .build()).join();
 *
 * messageService.send(Message.builder()
 *     .topic("user.message")
 *     .content("What's my name?")
 *     .build()).join();
 *
 * // Agent will remember "Alice" and respond appropriately
 * }</pre>
 *
 * @since 0.6.0
 */
@JenticAgent(value = "chat-agent")
public class ChatAgent extends LLMAgent {

    /**
     * Create a chat agent with auto-generated ID.
     */
    public ChatAgent() {
        super("chat-agent", "ChatAgent");
    }

    /**
     * Create a chat agent with specific ID.
     *
     * @param agentId the agent ID
     */
    public ChatAgent(String agentId) {
        super(agentId, "ChatAgent-" + agentId);
    }

    @Override
    protected void onStart() {
        log.info("ChatAgent started. Conversation history: {} messages, {} tokens",
                getConversationMessageCount(),
                getConversationTokens());

        // Configure for chat usage
        setDefaultStrategy(ContextWindowStrategies.SLIDING);
        setDefaultConversationBudget(2000);
        setDefaultContextBudget(500);

        // Configure auto-summarization (5000 tokens threshold, summarize oldest 10)
        configureAutoSummarization(5000, 10);

        // Add system message
        if (hasLLMMemory()) {
            addConversationMessage(LLMMessage.system(
                    "You are a helpful AI assistant. " +
                            "You remember facts about users and provide context-aware responses."
            )).join();
        }
    }

    /**
     * Handle incoming user messages.
     *
     * @param message the user message
     */
    @JenticMessageHandler("user.message")
    public void handleUserMessage(Message message) {
        String userInput = message.getContent(String.class);
        log.info("Received user message: {}", userInput);

        if (!hasLLMMemory()) {
            log.warn("LLM memory not configured, cannot process message");
            return;
        }

        // Add user message to conversation
        addConversationMessage(LLMMessage.user(userInput))
                .thenCompose(v -> {
                    // Extract facts from user message
                    return extractAndStoreFacts(userInput);
                })
                .thenCompose(v -> {
                    // Build prompt with conversation and relevant facts via LLMAgent API
                    return buildLLMPrompt(userInput, 2500, extractSearchKeyword(userInput));
                })
                .thenApply(prompt -> {
                    // Simulate LLM response (in real app, call actual LLM)
                    String response = simulateLLMResponse(prompt, userInput);
                    return response;
                })
                .thenCompose(response -> {
                    // Add response to conversation
                    return addConversationMessage(LLMMessage.assistant(response))
                            .thenApply(v -> response);
                })
                .thenAccept(response -> {
                    // Send response back
                    var responseMsg = Message.builder()
                            .topic("agent.response")
                            .senderId(getAgentId())
                            .content(response)
                            .header("agentId", getAgentId())
                            .header("conversationTokens", String.valueOf(getConversationTokens()))
                            .build();
                    getMessageDispatcher().publish(responseMsg.topic(), responseMsg).join();

                    log.info("Sent response: {} (total tokens: {})",
                            response, getConversationTokens());
                })
                .exceptionally(e -> {
                    log.error("Error processing message", e);
                    return null;
                });
    }

    /**
     * Handle clear conversation command.
     *
     * @param message the command message
     */
    @JenticMessageHandler("clear.conversation")
    public void handleClearConversation(Message message) {
        log.info("Clearing conversation history");

        clearConversation()
                .thenRun(() -> {
                    // Re-add system message
                    addConversationMessage(LLMMessage.system(
                            "You are a helpful AI assistant."
                    )).join();

                    log.info("Conversation cleared and reset");

                    // Send confirmation
                    var notifMsg = Message.builder()
                            .topic("agent.notification")
                            .senderId(getAgentId())
                            .content("Conversation history cleared")
                            .build();
                    getMessageDispatcher().publish(notifMsg.topic(), notifMsg).join();
                })
                .exceptionally(e -> {
                    log.error("Error clearing conversation", e);
                    return null;
                });
    }

    /**
     * Handle summarization command.
     *
     * @param message the command message
     */
    @JenticMessageHandler("summarize.conversation")
    public void handleSummarizeConversation(Message message) {
        // Parse count from header, default to 10
        int count = 10;
        String countHeader = message.headers().get("count");
        if (countHeader != null) {
            try {
                count = Integer.parseInt(countHeader);
            } catch (NumberFormatException e) {
                log.warn("Invalid count header: {}, using default", countHeader);
            }
        }

        log.info("Summarizing oldest {} messages", count);

        final int finalCount = count;
        summarizeConversation(count)
                .thenAccept(summary -> {
                    log.info("Summarization complete: {}", summary);

                    // Send summary
                    var summaryMsg = Message.builder()
                            .topic("agent.notification")
                            .senderId(getAgentId())
                            .content("Summarized " + finalCount + " messages: " + summary)
                            .build();
                    getMessageDispatcher().publish(summaryMsg.topic(), summaryMsg).join();
                })
                .exceptionally(e -> {
                    log.error("Error summarizing conversation", e);
                    return null;
                });
    }

    /**
     * Handle status query.
     *
     * @param message the query message
     */
    @JenticMessageHandler("query.status")
    public void handleStatusQuery(Message message) {
        Map<String, Object> status = Map.of(
                "agentId", getAgentId(),
                "running", isRunning(),
                "messageCount", getConversationMessageCount(),
                "tokenCount", getConversationTokens(),
                "autoSummarizeThreshold", autoSummarizeThreshold,
                "defaultStrategy", defaultStrategy.toString()
        );

        var statusMsg = Message.builder()
                .topic("agent.status")
                .senderId(getAgentId())
                .content(status)
                .build();
        getMessageDispatcher().publish(statusMsg.topic(), statusMsg).join();
    }

    /**
     * Extract and store important facts from user input.
     *
     * <p>In a real implementation, this would use NLP or an LLM
     * to extract facts. This is a simplified version for demo.
     *
     * @param userInput the user input
     * @return future that completes when facts are stored
     */
    private java.util.concurrent.CompletableFuture<Void> extractAndStoreFacts(String userInput) {
        return java.util.concurrent.CompletableFuture.runAsync(() -> {
            String lowerInput = userInput.toLowerCase();

            // Extract name
            if (lowerInput.contains("my name is ") || lowerInput.contains("i'm ") || lowerInput.contains("i am ")) {
                String name = extractName(userInput);
                if (name != null) {
                    storeFact("user-name", name, Map.of("category", "profile"))
                            .thenRun(() -> log.info("Stored fact: user-name = {}", name))
                            .join();
                }
            }

            // Extract location
            if (lowerInput.contains("from ") || lowerInput.contains("live in ")) {
                String location = extractLocation(userInput);
                if (location != null) {
                    storeFact("user-location", location, Map.of("category", "profile"))
                            .thenRun(() -> log.info("Stored fact: user-location = {}", location))
                            .join();
                }
            }

            // Extract preferences
            if (lowerInput.contains("i like ") || lowerInput.contains("i love ") || lowerInput.contains("i prefer ")) {
                String preference = extractPreference(userInput);
                if (preference != null) {
                    storeFact("user-preference-" + System.currentTimeMillis(),
                            preference,
                            Map.of("category", "preference"))
                            .thenRun(() -> log.info("Stored fact: preference = {}", preference))
                            .join();
                }
            }
        });
    }

    /**
     * Extract name from text (simplified).
     */
    private String extractName(String text) {
        // Simplified extraction: "my name is X" or "I'm X"
        String lower = text.toLowerCase();

        if (lower.contains("my name is ")) {
            int start = lower.indexOf("my name is ") + "my name is ".length();
            String rest = text.substring(start).trim();
            return rest.split("[,\\.\\s]")[0];
        }

        if (lower.contains("i'm ")) {
            int start = lower.indexOf("i'm ") + "i'm ".length();
            String rest = text.substring(start).trim();
            return rest.split("[,\\.\\s]")[0];
        }

        return null;
    }

    /**
     * Extract location from text (simplified).
     */
    private String extractLocation(String text) {
        String lower = text.toLowerCase();

        if (lower.contains("from ")) {
            int start = lower.indexOf("from ") + "from ".length();
            String rest = text.substring(start).trim();
            return rest.split("[,\\.\\s]")[0];
        }

        if (lower.contains("live in ")) {
            int start = lower.indexOf("live in ") + "live in ".length();
            String rest = text.substring(start).trim();
            return rest.split("[,\\.\\s]")[0];
        }

        return null;
    }

    /**
     * Extract preference from text (simplified).
     */
    private String extractPreference(String text) {
        String lower = text.toLowerCase();

        for (String prefix : List.of("i like ", "i love ", "i prefer ")) {
            if (lower.contains(prefix)) {
                int start = lower.indexOf(prefix) + prefix.length();
                String rest = text.substring(start).trim();
                // Get until punctuation
                int end = rest.length();
                for (int i = 0; i < rest.length(); i++) {
                    char c = rest.charAt(i);
                    if (c == '.' || c == ',' || c == '!') {
                        end = i;
                        break;
                    }
                }
                return rest.substring(0, end).trim();
            }
        }

        return null;
    }

    /**
     * Simulate LLM response (placeholder for actual LLM call).
     *
     * <p>In a real implementation, this would call an actual LLM API
     * with the prompt.
     *
     * @param prompt the complete prompt
     * @param userInput the original user input
     * @return simulated response
     */
    private String simulateLLMResponse(List<LLMMessage> prompt, String userInput) {
        // For demo purposes, generate simple context-aware responses
        String lower = userInput.toLowerCase();

        // Extract context from SYSTEM messages
        String context = prompt.stream()
                .filter(m -> m.role() == LLMMessage.Role.SYSTEM)
                .map(LLMMessage::content)
                .collect(Collectors.joining(" "));

        // Check for name query
        if (lower.contains("what") && (lower.contains("my name") || lower.contains("name"))) {
            // Try to find name in context
            String name = extractFactFromContext(context, "user-name");

            if (name != null) {
                return String.format("Your name is %s!", name);
            } else {
                return "I'm sorry, I don't know your name yet. Could you tell me?";
            }
        }

        // Check for location query
        if (lower.contains("where") && (lower.contains("from") || lower.contains("live"))) {
            String location = extractFactFromContext(context, "user-location");

            if (location != null) {
                return String.format("You're from %s!", location);
            } else {
                return "I can see location information in our conversation history if you've shared it.";
            }
        }

        // Generic greeting
        if (lower.contains("hello") || lower.contains("hi ")) {
            return "Hello! How can I help you today?";
        }

        // Default response
        return String.format("I understand you said: '%s'. " +
                        "I have %d messages in our conversation history. " +
                        "I can remember facts you tell me and retrieve them later.",
                userInput.substring(0, Math.min(50, userInput.length())),
                prompt.size());
    }

    /**
     * Extract search keyword from user input for better context retrieval.
     * Converts questions into searchable keywords.
     *
     * @param userInput the user's input
     * @return search keyword or null for no search
     */
    private String extractSearchKeyword(String userInput) {
        String lower = userInput.toLowerCase();

        // Name queries
        if (lower.contains("name")) {
            return "name";  // Will match "Alice" in content or "user-name" in metadata
        }

        // Location queries
        if (lower.contains("from") || lower.contains("location") || lower.contains("where") || lower.contains("live")) {
            return "location";  // Will match location-related facts
        }

        // Preference queries
        if (lower.contains("like") || lower.contains("prefer") || lower.contains("favorite") || lower.contains("love")) {
            return "prefer";  // Will match preferences
        }

        // Work/job queries
        if (lower.contains("work") || lower.contains("job") || lower.contains("profession")) {
            return "work";
        }

        // For other queries, use the full text (might not match well)
        return userInput;
    }

    /**
     * Build LLM prompt with conversation and ALL stored facts.
     *
     * <p>This method retrieves all facts without text filtering, then relies on
     * the LLM (or simulateLLMResponse) to extract relevant information.
     *
     * @param userMessage the new user message
     * @param totalBudget total token budget for prompt
     * @return future with complete prompt
     */
    private CompletableFuture<List<LLMMessage>> buildChatPrompt(
            String userMessage,
            int totalBudget
    ) {
        return CompletableFuture.supplyAsync(() -> {
            List<LLMMessage> prompt = new java.util.ArrayList<>();
            int conversationBudget = totalBudget;

            // Retrieve ALL facts (no text filter)
            MemoryQuery query = MemoryQuery.builder()
                    .scope(MemoryScope.LONG_TERM)
                    .ownerId(getAgentId())
                    .limit(50)
                    .build();

            List<MemoryEntry> allFacts = memoryStore.search(query).join();

            if (!allFacts.isEmpty()) {
                // Limit by token budget
                List<MemoryEntry> limitedFacts = filterFactsByTokens(allFacts, defaultContextBudget);

                if (!limitedFacts.isEmpty()) {
                    // Format with keys from metadata
                    String contextStr = limitedFacts.stream()
                            .map(entry -> {
                                String key = (String) entry.metadata().getOrDefault("key", "fact");
                                return key + ": " + entry.content();
                            })
                            .collect(Collectors.joining(", "));

                    prompt.add(LLMMessage.system("Relevant facts: " + contextStr));

                    // Reduce conversation budget
                    int contextTokens = getLLMMemoryManager().getTokenEstimator()
                            .estimateTokens(prompt.get(0));
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

    /**
     * Filter facts by token budget.
     */
    private List<MemoryEntry> filterFactsByTokens(List<MemoryEntry> facts, int maxTokens) {
        List<MemoryEntry> filtered = new java.util.ArrayList<>();
        int currentTokens = 0;

        for (MemoryEntry fact : facts) {
            int tokens = fact.tokenCount();
            if (currentTokens + tokens <= maxTokens) {
                filtered.add(fact);
                currentTokens += tokens;
            } else {
                break;
            }
        }

        return filtered;
    }

    /**
     * Extract a fact value from context string.
     * Context format: "Relevant facts: key1: value1, key2: value2"
     */
    private String extractFactFromContext(String context, String key) {
        if (context == null || context.isEmpty()) {
            return null;
        }

        // Look for "key: value" pattern
        String pattern = key + ":\\s*([^,]+)";
        java.util.regex.Pattern p = java.util.regex.Pattern.compile(pattern);
        java.util.regex.Matcher m = p.matcher(context);

        if (m.find()) {
            return m.group(1).trim();
        }

        return null;
    }

    @Override
    protected void onStop() {
        log.info("ChatAgent stopping. Final state: {} messages, {} tokens",
                getConversationMessageCount(),
                getConversationTokens());
    }
}