package dev.agenor.examples.dialogue;

import dev.agenor.core.Agent;
import dev.agenor.core.AgentDescriptor;
import dev.agenor.core.AgentStatus;
import dev.agenor.core.Behavior;
import dev.agenor.core.dialogue.DialogueHandler;
import dev.agenor.core.dialogue.DialogueMessage;
import dev.agenor.core.dialogue.Performative;
import dev.agenor.core.messaging.MessageDispatcher;
import dev.agenor.runtime.dialogue.DialogueCapability;
import dev.agenor.runtime.directory.InMemoryAgentDirectory;
import dev.agenor.runtime.messaging.InMemoryMessageDispatcher;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Runnable example: Query Protocol (Knowledge Base).
 *
 * <p>Run with:
 * <pre>
 * mvn exec:java -pl agenor-examples \
 *     -Dexec.mainClass="dev.agenor.examples.dialogue.QueryProtocolExample"
 * </pre>
 */
public class QueryProtocolExample {

    public static void main(String[] args) throws Exception {
        System.out.println("╔══════════════════════════════════════════════════════════╗");
        System.out.println("║      QUERY PROTOCOL EXAMPLE - Knowledge Retrieval        ║");
        System.out.println("╚══════════════════════════════════════════════════════════╝\n");

        // Shared infrastructure — agents must be registered so sendTo can resolve endpoints
        InMemoryAgentDirectory directory = new InMemoryAgentDirectory();
        InMemoryMessageDispatcher dispatcher = new InMemoryMessageDispatcher(directory);

        // Create agents
        KnowledgeBase kb = new KnowledgeBase(dispatcher, directory);
        QueryClient client = new QueryClient(dispatcher, directory);

        // Start agents
        kb.start().join();
        client.start().join();
        Thread.sleep(100);

        // === Queries ===
        String[] queries = {
            "capital:France",
            "capital:Japan",
            "population:Germany",
            "unknown:xyz",
            "status"
        };

        for (int i = 0; i < queries.length; i++) {
            String q = queries[i];
            System.out.println("┌─────────────────────────────────────────────────────────┐");
            System.out.printf("│ Query %d: %-46s │%n", i + 1, q);
            System.out.println("└─────────────────────────────────────────────────────────┘");

            DialogueMessage r = client.ask(q).get(5, TimeUnit.SECONDS);
            System.out.println("Response: " + r.performative() + " - " + r.content() + "\n");
        }

        // Cleanup
        client.stop().join();
        kb.stop().join();

        System.out.println("╔══════════════════════════════════════════════════════════╗");
        System.out.println("║                    EXAMPLE COMPLETE                      ║");
        System.out.println("╚══════════════════════════════════════════════════════════╝");
    }

    // =========================================================================
    // KNOWLEDGE BASE AGENT
    // =========================================================================

    static class KnowledgeBase implements Agent {

        private final MessageDispatcher dispatcher;
        private final InMemoryAgentDirectory directory;
        private final DialogueCapability dialogue = new DialogueCapability(this);
        private boolean running;

        // Simple KB
        private final Map<String, String> facts = Map.of(
            "capital:France", "Paris",
            "capital:Germany", "Berlin",
            "capital:Japan", "Tokyo",
            "capital:Italy", "Rome",
            "population:Japan", "125 million",
            "population:Germany", "83 million"
        );

        KnowledgeBase(MessageDispatcher ms, InMemoryAgentDirectory dir) {
            this.dispatcher = ms;
            this.directory = dir;
        }

        @Override public String getAgentId() { return "kb"; }
        @Override public String getAgentName() { return "Knowledge Base"; }
        @Override public boolean isRunning() { return running; }
        @Override public void addBehavior(Behavior b) {}
        @Override public void removeBehavior(String id) {}
        @Override public MessageDispatcher getMessageDispatcher() { return dispatcher; }

        @Override
        public CompletableFuture<Void> start() {
            return CompletableFuture.runAsync(() -> {
                directory.register(AgentDescriptor.builder("kb")
                        .agentName("Knowledge Base").agentType("KnowledgeBase")
                        .status(AgentStatus.RUNNING).build()).join();
                dialogue.initialize(dispatcher);
                running = true;
                System.out.println("[KB] Started with " + facts.size() + " facts");
            });
        }

        @Override
        public CompletableFuture<Void> stop() {
            return CompletableFuture.runAsync(() -> {
                dialogue.shutdown();
                directory.unregister("kb").join();
                running = false;
            });
        }

        @DialogueHandler(performatives = Performative.QUERY)
        public void handleQuery(DialogueMessage msg) {
            String query = msg.content() != null ? msg.content().toString() : "";
            System.out.println("[KB] Query: " + query);

            if ("status".equals(query)) {
                dialogue.inform(msg, "Online. Facts: " + facts.size());
                return;
            }

            String answer = facts.get(query);
            if (answer != null) {
                System.out.println("[KB] Found: " + answer);
                dialogue.inform(msg, answer);
            } else {
                System.out.println("[KB] Not found");
                dialogue.refuse(msg, "Unknown: " + query);
            }
        }
    }

    // =========================================================================
    // QUERY CLIENT
    // =========================================================================

    static class QueryClient implements Agent {

        private final MessageDispatcher dispatcher;
        private final InMemoryAgentDirectory directory;
        private final DialogueCapability dialogue = new DialogueCapability(this);
        private boolean running;

        QueryClient(MessageDispatcher ms, InMemoryAgentDirectory dir) {
            this.dispatcher = ms;
            this.directory = dir;
        }

        @Override public String getAgentId() { return "client"; }
        @Override public String getAgentName() { return "Client"; }
        @Override public boolean isRunning() { return running; }
        @Override public void addBehavior(Behavior b) {}
        @Override public void removeBehavior(String id) {}
        @Override public MessageDispatcher getMessageDispatcher() { return dispatcher; }

        @Override
        public CompletableFuture<Void> start() {
            return CompletableFuture.runAsync(() -> {
                directory.register(AgentDescriptor.builder("client")
                        .agentName("Client").agentType("QueryClient")
                        .status(AgentStatus.RUNNING).build()).join();
                dialogue.initialize(dispatcher);
                running = true;
                System.out.println("[Client] Started\n");
            });
        }

        @Override
        public CompletableFuture<Void> stop() {
            return CompletableFuture.runAsync(() -> {
                dialogue.shutdown();
                directory.unregister("client").join();
                running = false;
            });
        }

        CompletableFuture<DialogueMessage> ask(String query) {
            System.out.println("[Client] Asking: " + query);
            return dialogue.query("kb", query, Duration.ofSeconds(5));
        }
    }
}
