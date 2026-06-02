package dev.agenor.examples.memory;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import dev.agenor.core.BehaviorType;
import dev.agenor.core.Message;
import dev.agenor.core.annotations.AgenorMessageHandler;
import dev.agenor.core.annotations.Agent;
import dev.agenor.core.messaging.MessageDispatcher;
import dev.agenor.core.annotations.Behavior;
import dev.agenor.core.memory.MemoryScope;
import dev.agenor.core.memory.MemoryStats;
import dev.agenor.runtime.JenticRuntime;
import dev.agenor.runtime.agent.BaseAgent;
import dev.agenor.runtime.memory.InMemoryStore;

/**
 * Complete runnable example demonstrating memory management with JenticRuntime.
 *
 * <p>This example shows the <b>proper integration pattern</b> using JenticRuntime
 * for automatic agent discovery, annotation processing, and dependency injection.
 *
 * <p><b>Features demonstrated:</b>
 * <ul>
 *   <li>JenticRuntime with automatic agent discovery</li>
 *   <li>MemoryStore integration via builder</li>
 *   <li>@AgenorMessageHandler automatic registration</li>
 *   <li>@Behavior automatic scheduling</li>
 *   <li>Short-term and long-term memory</li>
 *   <li>Automatic state persistence</li>
 * </ul>
 *
 * <p><b>How to run:</b>
 * <pre>{@code
 * java dev.agenor.examples.memory.UserPreferenceMemoryExample
 * }</pre>
 *
 * @since 0.6.0
 */
public class UserPreferenceMemoryExample {

    public static void main(String[] args) throws Exception {
        System.out.println("=== Jentic Memory Management with Runtime ===\n");

        // 1. Create memory store
        InMemoryStore memoryStore = new InMemoryStore();

        // 2. Create runtime with automatic discovery and memory support
        System.out.println("Creating runtime with agent discovery...");
        JenticRuntime runtime = JenticRuntime.builder()
            .scanPackages("dev.agenor.examples.memory")  // Scan for @Agent
            .memoryStore(memoryStore)                     // Enable memory features
            .build();

        // 3. Start runtime (discovers and starts agents automatically)
        System.out.println("Starting runtime...");
        runtime.start().join();

        // 4. Get the discovered agent
        UserPreferenceAgent agent = (UserPreferenceAgent) runtime
            .getAgent("user-preference-agent")
            .orElseThrow(() -> new RuntimeException("Agent not found!"));

        System.out.println("Agent discovered and started: " + agent.getAgentId());
        System.out.println("Runtime stats: " + runtime.getStats() + "\n");

        // 5. Simulate user interactions
        simulateInteractions(runtime.getMessageDispatcher(), agent.getAgentId());

        // 6. Wait for processing
        Thread.sleep(2000);

        // 7. Display statistics
        displayMemoryStats(agent);

        // 8. Stop runtime (saves agent state)
        System.out.println("\nStopping runtime...");
        runtime.stop().join();
        System.out.println("Runtime stopped (state saved)\n");

        // 9. Restart runtime (restores agent state)
        System.out.println("Restarting runtime...");
        runtime.start().join();

        // Get agent again after restart
        agent = (UserPreferenceAgent) runtime
            .getAgent("user-preference-agent")
            .orElseThrow();

        System.out.println("Runtime restarted (state restored)");
        displayMemoryStats(agent);

        // 10. Cleanup
        runtime.stop().join();
        memoryStore.shutdown();

        System.out.println("\n=== Example completed ===");
    }

    private static void simulateInteractions(MessageDispatcher dispatcher, String agentId) {
        System.out.println("Simulating user interactions:\n");

        // 1. Update user preference
        System.out.println("1. Updating user preference...");
        Message prefUpdate = Message.builder()
            .topic("user.preference.update")
            .senderId("example-client")
            .content("dark-mode")
            .header("userId", "user123")
            .build();
        dispatcher.publish(prefUpdate);
        sleep(300);

        // 2. Track interactions
        System.out.println("2. Tracking user interactions...");
        for (int i = 1; i <= 3; i++) {
            Message interaction = Message.builder()
                .topic("user.interaction")
                .senderId("example-client")
                .content("Clicked button " + i)
                .header("userId", "user123")
                .build();
            dispatcher.publish(interaction);
            sleep(200);
        }

        // 3. Get preference
        System.out.println("3. Retrieving user preference...");
        Message prefGet = Message.builder()
            .topic("user.preference.get")
            .senderId("example-client")
            .header("userId", "user123")
            .build();
        dispatcher.publish(prefGet);
        sleep(300);

        // 4. Search history
        System.out.println("4. Searching user history...");
        Message historySearch = Message.builder()
            .topic("user.history.search")
            .senderId("example-client")
            .content("dark")
            .build();
        dispatcher.publish(historySearch);
        sleep(300);
    }

    private static void displayMemoryStats(UserPreferenceAgent agent) {
        try {
            MemoryStats stats = agent.getMemoryStats();
            System.out.println("\nMemory Statistics:");
            System.out.println("  Short-term entries: " + stats.shortTermCount());
            System.out.println("  Long-term entries: " + stats.longTermCount());
            System.out.println("  Total entries: " + stats.totalCount());
            System.out.println("  Estimated tokens: " + stats.estimatedTokens());
            System.out.println("  Memory size: " + String.format("%.2f KB", stats.estimatedSizeKB()));
            System.out.println("  Interaction count: " + agent.getInteractionCount());
        } catch (Exception e) {
            System.out.println("Could not retrieve stats: " + e.getMessage());
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}

/**
 * Agent that tracks user preferences using memory features.
 *
 * <p>This agent is automatically discovered by JenticRuntime through
 * package scanning and configured with all required services including
 * the MemoryStore.
 *
 * <p><b>Key features:</b>
 * <ul>
 *   <li>@Agent for discovery</li>
 *   <li>@AgenorMessageHandler for automatic subscription</li>
 *   <li>@Behavior for scheduled tasks (DISABLED in this example)</li>
 *   <li>Memory methods from BaseAgent</li>
 * </ul>
 */
@Agent(
    value = "user-preference-agent",
    type = "PreferenceTracker",
    capabilities = {"memory-management", "preference-tracking"},
    autoStart = true  // Start automatically when runtime starts
)
class UserPreferenceAgent extends BaseAgent {

    private int interactionCount = 0;

    public UserPreferenceAgent() {
        super("user-preference-agent", "User Preference Agent");
    }

    public int getInteractionCount() {
        return interactionCount;
    }

    @Override
    protected void onStart() {
        log.info("User Preference Agent started");

        // Load previous interaction count from long-term memory
        recall("interaction-count", MemoryScope.LONG_TERM)
            .thenAccept(count -> {
                if (count.isPresent()) {
                    interactionCount = Integer.parseInt(count.get());
                    log.info("Restored interaction count: {}", interactionCount);
                    System.out.println("  Restored interaction count: " + interactionCount);
                } else {
                    log.info("Starting fresh with interaction count: 0");
                }
            })
            .exceptionally(ex -> {
                log.debug("Memory store not available: {}", ex.getMessage());
                return null;
            });
    }

    @Override
    protected void onStop() {
        log.info("User Preference Agent stopping...");

        // Save interaction count before stopping
        rememberLong("interaction-count", String.valueOf(interactionCount))
            .thenRun(() -> {
                log.info("Saved interaction count: {}", interactionCount);
                System.out.println("  Saved interaction count: " + interactionCount);
            })
            .exceptionally(ex -> {
                log.warn("Failed to save interaction count: {}", ex.getMessage());
                return null;
            })
            .join(); // Wait for save to complete
    }

    /**
     * Handles user preference updates.
     * Automatically subscribed via @AgenorMessageHandler by AnnotationProcessor.
     */
    @AgenorMessageHandler("user.preference.update")
    public void handlePreferenceUpdate(Message message) {
        String userId = message.headers().get("userId");
        String preference = message.getContent(String.class);

        log.info("Updating preference for user {}: {}", userId, preference);
        System.out.println("  → Saving preference: " + preference + " for user: " + userId);

        // Store user preference in long-term memory with metadata
        Map<String, Object> metadata = Map.of(
            "userId", userId,
            "category", "preference",
            "updatedAt", Instant.now().toString()
        );

        rememberLong("user:" + userId + ":preference", preference, metadata)
            .thenRun(() -> {
                log.info("Successfully updated preference for user {}", userId);
                System.out.println("  ✓ Preference saved");
            })
            .exceptionally(ex -> {
                log.error("Failed to save preference for user {}", userId, ex);
                System.err.println("  ✗ Failed to save preference: " + ex.getMessage());
                return null;
            });
    }

    /**
     * Retrieves user preferences.
     */
    @AgenorMessageHandler("user.preference.get")
    public void handlePreferenceGet(Message message) {
        String userId = message.headers().get("userId");

        log.info("Retrieving preference for user {}", userId);
        System.out.println("  → Retrieving preference for user: " + userId);

        recall("user:" + userId + ":preference", MemoryScope.LONG_TERM)
            .thenAccept(preference -> {
                String response = preference.orElse("No preference set");
                log.info("Retrieved preference for user {}: {}", userId, response);
                System.out.println("  ✓ Found preference: " + response);
            })
            .exceptionally(ex -> {
                log.error("Failed to retrieve preference for user {}", userId, ex);
                return null;
            });
    }

    /**
     * Tracks user interactions with short-term memory (1-hour TTL).
     */
    @AgenorMessageHandler("user.interaction")
    public void handleInteraction(Message message) {
        String userId = message.headers().get("userId");
        String interaction = message.getContent(String.class);

        interactionCount++;

        log.debug("Tracked interaction #{} for user {}: {}", interactionCount, userId, interaction);
        System.out.println("  → Interaction #" + interactionCount + ": " + interaction);

        // Store interaction in short-term memory with 1-hour TTL
        String interactionKey = "user:" + userId + ":last-interaction";
        String interactionData = String.format("%s at %s", interaction, Instant.now());

        rememberShort(interactionKey, interactionData, Duration.ofHours(1))
            .exceptionally(ex -> {
                log.warn("Failed to track interaction: {}", ex.getMessage());
                return null;
            });
    }

    /**
     * Searches user history across all stored memories.
     */
    @AgenorMessageHandler("user.history.search")
    public void handleHistorySearch(Message message) {
        String query = message.getContent(String.class);

        log.info("Searching history for query: '{}'", query);
        System.out.println("  → Searching history for: " + query);

        searchMemory(query, MemoryScope.LONG_TERM)
            .thenAccept(results -> {
                log.info("Found {} history entries for query: '{}'", results.size(), query);
                System.out.println("  ✓ Found " + results.size() + " entries");

                for (int i = 0; i < Math.min(3, results.size()); i++) {
                    System.out.println("    - " + results.get(i));
                }
            })
            .exceptionally(ex -> {
                log.error("Failed to search history for query: '{}'", query, ex);
                return null;
            });
    }

    /**
     * Periodically logs memory statistics.
     *
     * NOTE: Disabled (autoStart=false) to avoid noise in example output.
     * Enable by setting autoStart=true to see periodic stats.
     */
    @Behavior(
        type = BehaviorType.CYCLIC,
        interval = "5m",
        autoStart = false  // Disabled for cleaner example output
    )
    public void logMemoryStats() {
        try {
            MemoryStats stats = getMemoryStats();

            log.info("Memory Stats - Short-term: {}, Long-term: {}, Total: {}, Tokens: {}",
                     stats.shortTermCount(),
                     stats.longTermCount(),
                     stats.totalCount(),
                     stats.estimatedTokens());
        } catch (IllegalStateException e) {
            log.debug("Memory store not available for stats");
        }
    }
}
