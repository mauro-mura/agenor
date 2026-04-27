package dev.jentic.examples.dialogue;

import dev.jentic.core.annotations.JenticAgent;
import dev.jentic.core.dialogue.DialogueHandler;
import dev.jentic.core.dialogue.DialogueMessage;
import dev.jentic.core.dialogue.Performative;
import dev.jentic.runtime.JenticRuntime;
import dev.jentic.runtime.agent.BaseAgent;
import dev.jentic.runtime.dialogue.DialogueCapability;

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
 * mvn exec:java -pl jentic-examples \
 *     -Dexec.mainClass="dev.jentic.examples.dialogue.ContractNetExample"
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

        JenticRuntime runtime = JenticRuntime.builder()
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

    @JenticAgent("manager")
    static class Manager extends BaseAgent {

        private final DialogueCapability dialogue = new DialogueCapability(this);

        @Override public String getAgentId() { return "manager"; }
        @Override public String getAgentName() { return "Manager"; }

        @Override
        protected void onStart() {
            super.onStart();
            dialogue.initialize(getMessageDispatcher());
            System.out.println("[Manager] Started");
        }

        @Override
        protected void onStop() {
            super.onStop();
            dialogue.shutdown();
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
                    
                    // Print all proposals
                    System.out.println("\n[Manager] Proposals:");
                    for (DialogueMessage p : proposals) {
                        Bid bid = (Bid) p.content();
                        System.out.printf("  - %s: cost=%.2f, time=%ds%n", 
                            p.senderId(), bid.cost(), bid.timeSeconds());
                    }
                    
                    // Select best (lowest cost)
                    DialogueMessage best = proposals.stream()
                        .min(Comparator.comparingDouble(p -> ((Bid) p.content()).cost()))
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

    @JenticAgent("worker")
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
            dialogue.initialize(getMessageDispatcher());
            System.out.printf("[%s] Started (efficiency: %.0f%%)%n", id, efficiency * 100);
        }

        @Override
        protected void onStop() {
            super.onStop();
            dialogue.shutdown();
        }

        @DialogueHandler(performatives = Performative.CFP)
        public void handleCFP(DialogueMessage msg) {
            System.out.println("[" + id + "] Received CFP");
            
            if (!(msg.content() instanceof Task task)) {
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