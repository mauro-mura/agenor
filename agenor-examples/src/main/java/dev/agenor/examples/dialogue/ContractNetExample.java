package dev.agenor.examples.dialogue;

import dev.agenor.core.annotations.Agent;
import dev.agenor.core.dialogue.DialogueHandler;
import dev.agenor.core.dialogue.DialogueMessage;
import dev.agenor.core.dialogue.Performative;
import dev.agenor.runtime.AgenorRuntime;
import dev.agenor.runtime.agent.BaseAgent;
import dev.agenor.runtime.dialogue.DialogueCapability;

import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Runnable example: Contract-Net Protocol (Task Allocation).
 *
 * <p>Run with:
 * <pre>
 * mvn exec:java -pl agenor-examples \
 *     -Dexec.mainClass="dev.agenor.examples.dialogue.ContractNetExample"
 * </pre>
 */
public class ContractNetExample {

    public static void main(String[] args) throws Exception {
        System.out.println("╔══════════════════════════════════════════════════════════╗");
        System.out.println("║    CONTRACT-NET PROTOCOL EXAMPLE - Task Allocation       ║");
        System.out.println("╚══════════════════════════════════════════════════════════╝\n");

        // Create manager
        Manager manager = new Manager();

        // Create workers with different efficiencies
        Worker w1 = new Worker("worker-1", 0.6);
        Worker w2 = new Worker("worker-2", 0.9);
        Worker w3 = new Worker("worker-3", 0.4);

        AgenorRuntime runtime = AgenorRuntime.builder()
                .build();

        runtime.registerAgent(manager);
        runtime.registerAgent(w1);
        runtime.registerAgent(w2);
        runtime.registerAgent(w3);

        // Start all
        runtime.start().join();
        Thread.sleep(200);

        // Allocate task
        System.out.println("┌─────────────────────────────────────────────────────────┐");
        System.out.println("│ Task: data-processing (complexity: 100)                 │");
        System.out.println("└─────────────────────────────────────────────────────────┘\n");

        Task task = new Task("data-processing", 100);
        List<String> workers = List.of("worker-1", "worker-2", "worker-3");

        String winner = manager.allocateTask(task, workers).get(15, TimeUnit.SECONDS);

        System.out.println("\n┌─────────────────────────────────────────────────────────┐");
        System.out.printf("│ WINNER: %-47s │%n", winner);
        System.out.println("└─────────────────────────────────────────────────────────┘");

        // Wait for task completion
        Thread.sleep(1500);

        // Cleanup
        runtime.stop().join();

        System.out.println("\n╔══════════════════════════════════════════════════════════╗");
        System.out.println("║                    EXAMPLE COMPLETE                      ║");
        System.out.println("╚══════════════════════════════════════════════════════════╝");
    }

    // =========================================================================
    // MANAGER AGENT (Initiator)
    // =========================================================================

    @Agent("manager")
    static class Manager extends BaseAgent {

        private final DialogueCapability dialogue = new DialogueCapability(this);

        @Override public String getAgentId() { return "manager"; }
        @Override public String getAgentName() { return "Manager"; }

        @Override
        protected void onStart() {
            super.onStart();
            // dialogue initializes itself via BaseAgent's lifecycle hooks
            System.out.println("[Manager] Started");
        }

        CompletableFuture<String> allocateTask(Task task, List<String> workerIds) {
            System.out.println("[Manager] Broadcasting CFP to " + workerIds.size() + " workers\n");

            return dialogue.callForProposals(workerIds, task, Duration.ofSeconds(10))
                .thenCompose(responses -> {
                    System.out.println("[Manager] Received " + responses.size() + " responses");

                    // Filter PROPOSE
                    List<DialogueMessage> proposals = responses.stream()
                        .filter(r -> r.performative() == Performative.PROPOSE)
                        .toList();

                    if (proposals.isEmpty()) {
                        return CompletableFuture.completedFuture("NO_PROPOSALS");
                    }

                    // Print all proposals.
                    // contentAs(), not a cast: a cast to Bid works here only because every
                    // agent shares one JVM. Over a serialising transport the payload arrives
                    // as a Map and the cast throws — see ADR-030.
                    System.out.println("\n[Manager] Proposals:");
                    for (DialogueMessage p : proposals) {
                        Bid bid = p.contentAs(Bid.class);
                        System.out.printf("  - %s: cost=%.2f, time=%ds%n",
                            p.senderId(), bid.cost(), bid.timeSeconds());
                    }

                    // Select best (lowest cost)
                    DialogueMessage best = proposals.stream()
                        .min(Comparator.comparingDouble(p -> p.contentAs(Bid.class).cost()))
                        .orElseThrow();

                    System.out.println("\n[Manager] Selected: " + best.senderId());
                    dialogue.reply(best, Performative.AGREE, "You win!");

                    return CompletableFuture.completedFuture(best.senderId());
                });
        }

        @DialogueHandler(performatives = Performative.INFORM)
        public void onComplete(DialogueMessage msg) {
            System.out.println("[Manager] Task completed by " + msg.senderId() + ": " + msg.content());
        }
    }

    // =========================================================================
    // WORKER AGENT (Participant)
    // =========================================================================

    @Agent(type = "worker")
    static class Worker extends BaseAgent {

        private final String id;
        private final double efficiency;
        private final DialogueCapability dialogue = new DialogueCapability(this);
        private final Random random = new Random();

        Worker(String id, double efficiency) {
            this.id = id;
            this.efficiency = efficiency;
        }

        @Override public String getAgentId() { return id; }
        @Override public String getAgentName() { return "Worker " + id; }

        @Override
        protected void onStart() {
            super.onStart();
            // dialogue initializes itself via BaseAgent's lifecycle hooks
            System.out.printf("[%s] Started (efficiency: %.0f%%)%n", id, efficiency * 100);
        }

        @DialogueHandler(performatives = Performative.CFP)
        public void handleCFP(DialogueMessage msg) {
            System.out.println("[" + id + "] Received CFP");

            // `instanceof Task` would fail for the same reason a cast would: after a
            // serialising transport the payload is a Map, not a Task. Conversion is lenient
            // about fields it does not recognise, so an unrelated payload yields a Task with
            // null fields rather than throwing — hence the explicit check (ADR-030).
            Task task;
            try {
                task = msg.contentAs(Task.class);
            } catch (IllegalArgumentException e) {
                task = null;
            }
            if (task == null || task.type() == null) {
                dialogue.refuse(msg, "Invalid task");
                return;
            }

            // Calculate bid
            double cost = task.complexity() / efficiency * (0.9 + random.nextDouble() * 0.2);
            int time = (int) (task.complexity() / 10 / efficiency);

            Bid bid = new Bid(cost, time);
            System.out.printf("[%s] PROPOSE: cost=%.2f%n", id, cost);
            dialogue.propose(msg, bid);
        }

        @DialogueHandler(performatives = Performative.AGREE)
        public void handleAccept(DialogueMessage msg) {
            System.out.println("[" + id + "] SELECTED! Executing task...");

            CompletableFuture.runAsync(() -> {
                try {
                    Thread.sleep(500 + random.nextInt(500));
                    System.out.println("[" + id + "] Task done!");
                    dialogue.inform(msg, "SUCCESS");
                } catch (Exception e) {
                    dialogue.failure(msg, e.getMessage());
                }
            });
        }
    }

    // =========================================================================
    // DATA
    // =========================================================================

    record Task(String type, int complexity) {}
    record Bid(double cost, int timeSeconds) {}
}
