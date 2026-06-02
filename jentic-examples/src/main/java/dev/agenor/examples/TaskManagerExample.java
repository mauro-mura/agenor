package dev.agenor.examples;

import static dev.agenor.core.BehaviorType.CYCLIC;

import java.time.LocalDateTime;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ThreadLocalRandom;

import dev.agenor.runtime.AgenorRuntime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.agenor.core.Message;
import dev.agenor.core.annotations.Agent;
import dev.agenor.core.annotations.Behavior;
import dev.agenor.core.annotations.AgenorMessageHandler;
import dev.agenor.runtime.agent.BaseAgent;

/**
 * Task management example with task creator, task processor, and task monitor agents.
 */
public class TaskManagerExample {

    private static final Logger log = LoggerFactory.getLogger(TaskManagerExample.class);

    public static void main(String[] args) throws InterruptedException {
        log.info("=== Jentic Task Manager Example ===");

        AgenorRuntime runtime = AgenorRuntime.builder().build();

        // Register agents
        runtime.registerAgent(new TaskCreatorAgent());
        runtime.registerAgent(new TaskProcessorAgent("processor-1"));
        runtime.registerAgent(new TaskProcessorAgent("processor-2"));
        runtime.registerAgent(new TaskMonitorAgent());

        // Start runtime
        runtime.start().join();

        log.info("Task manager started with {} agents", runtime.getAgents().size());

        // Run for 25 seconds
        Thread.sleep(25_000);

        // Stop runtime
        log.info("Stopping task manager...");
        runtime.stop().join();

        log.info("=== Task Manager Example completed ===");
    }

    // Task record
    public record Task(
        String id,
        String type,
        String description,
        TaskStatus status,
        LocalDateTime createdAt,
        LocalDateTime processedAt,
        String processedBy
    ) {
        public Task withStatus(TaskStatus newStatus) {
            return new Task(id, type, description, newStatus, createdAt, processedAt, processedBy);
        }

        public Task withProcessed(String processedBy) {
            return new Task(id, type, description, status, createdAt, LocalDateTime.now(), processedBy);
        }
    }

    public enum TaskStatus {
        CREATED, PROCESSING, COMPLETED, FAILED
    }

    /**
     * Agent that creates tasks periodically
     */
    @Agent(value = "task-creator",
                 type = "creator",
                 capabilities = {"task-creation"},
                 autoStart = true)
    public static class TaskCreatorAgent extends BaseAgent {

        private final String[] taskTypes = {"DATA_PROCESSING", "EMAIL_SEND", "FILE_BACKUP", "REPORT_GENERATION", "DATABASE_CLEANUP"};
        private int taskCounter = 0;

        public TaskCreatorAgent() {
            super("task-creator", "Task Creator");
        }

        @Behavior(type = CYCLIC, interval = "4s", autoStart = true)
        public void createTask() {
            taskCounter++;
            String taskType = taskTypes[ThreadLocalRandom.current().nextInt(taskTypes.length)];

            Task task = new Task(
                "task-" + taskCounter,
                taskType,
                "Task #" + taskCounter + " of type " + taskType,
                TaskStatus.CREATED,
                LocalDateTime.now(),
                null,
                null
            );

            Message taskMessage = Message.builder()
                .topic("tasks.new")
                .senderId(getAgentId())
                .content(task)
                .header("task-id", task.id())
                .header("task-type", task.type())
                .build();

            log.info("Created task: {} - {}", task.id(), task.description());
            getMessageDispatcher().publish(taskMessage);
        }

        @Override
        protected void onStart() {
            log.info("Task Creator started, will create tasks every 4 seconds");
        }
    }

    /**
     * Agent that processes tasks
     */
    @Agent(type = "processor",
                 capabilities = {"task-processing"},
                 autoStart = true)
    public static class TaskProcessorAgent extends BaseAgent {

        private final Queue<Task> processingQueue = new ConcurrentLinkedQueue<>();
        private int processedCount = 0;

        public TaskProcessorAgent(String processorId) {
            super(processorId, "Task Processor " + processorId);
        }

        @AgenorMessageHandler("tasks.new")
        public void receiveNewTask(Message message) {
            Task task = message.getContent(Task.class);
            processingQueue.offer(task);

            log.info("[{}] Queued task: {} (queue size: {})",
                    getAgentId(), task.id(), processingQueue.size());

            // Notify that task is being processed
            Message processingMessage = Message.builder()
                .topic("tasks.status")
                .senderId(getAgentId())
                .content(task.withStatus(TaskStatus.PROCESSING))
                .header("status", "PROCESSING")
                .header("processor", getAgentId())
                .build();

            getMessageDispatcher().publish(processingMessage);
        }

        @Behavior(type = CYCLIC, interval = "2s", autoStart = true)
        public void processTasks() {
            Task task = processingQueue.poll();
            if (task == null) {
                return; // No tasks to process
            }

            // Simulate processing time
            CompletableFuture.runAsync(() -> {
                try {
                    // Random processing time between 1-3 seconds
                    Thread.sleep(1000 + ThreadLocalRandom.current().nextInt(2000));

                    processedCount++;
                    boolean success = ThreadLocalRandom.current().nextDouble() < 0.9; // 90% success rate

                    Task completedTask = task
                        .withStatus(success ? TaskStatus.COMPLETED : TaskStatus.FAILED)
                        .withProcessed(getAgentId());

                    Message completedMessage = Message.builder()
                        .topic("tasks.completed")
                        .senderId(getAgentId())
                        .content(completedTask)
                        .header("status", completedTask.status().toString())
                        .header("processor", getAgentId())
                        .header("success", String.valueOf(success))
                        .build();

                    if (success) {
                        log.info("[{}] ✅ Completed task: {} (total processed: {})",
                                getAgentId(), task.id(), processedCount);
                    } else {
                        log.warn("[{}] ❌ Failed task: {} (total processed: {})",
                                getAgentId(), task.id(), processedCount);
                    }

                    getMessageDispatcher().publish(completedMessage);

                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.warn("Task processing interrupted: {}", task.id());
                }
            });
        }

        @Override
        protected void onStart() {
            log.info("[{}] Task Processor ready to handle tasks", getAgentId());
        }
    }

    /**
     * Agent that monitors task completion and maintains statistics
     */
    @Agent(value = "task-monitor",
                 type = "monitor",
                 capabilities = {"monitoring", "statistics"},
                 autoStart = true)
    public static class TaskMonitorAgent extends BaseAgent {

        private int totalTasks = 0;
        private int completedTasks = 0;
        private int failedTasks = 0;

        public TaskMonitorAgent() {
            super("task-monitor", "Task Monitor");
        }

        @AgenorMessageHandler("tasks.new")
        public void trackNewTask(Message message) {
            totalTasks++;
            Task task = message.getContent(Task.class);
            log.debug("Tracking new task: {} (total: {})", task.id(), totalTasks);
        }

        @AgenorMessageHandler("tasks.completed")
        public void trackCompletedTask(Message message) {
            Task task = message.getContent(Task.class);
            String processor = message.headers().get("processor");

            if (task.status() == TaskStatus.COMPLETED) {
                completedTasks++;
                log.info("📊 Task {} completed by {} (Success: {}/{})",
                        task.id(), processor, completedTasks, totalTasks);
            } else {
                failedTasks++;
                log.warn("📊 Task {} failed by {} (Failed: {}/{})",
                        task.id(), processor, failedTasks, totalTasks);
            }
        }

        @Behavior(type = CYCLIC, interval = "10s", autoStart = true)
        public void reportStatistics() {
            int pendingTasks = totalTasks - completedTasks - failedTasks;
            double successRate = totalTasks > 0 ? (double) completedTasks / totalTasks * 100 : 0;

            log.info("📈 STATISTICS - Total: {}, Completed: {}, Failed: {}, Pending: {}, Success Rate: {:.1f}%",
                    totalTasks, completedTasks, failedTasks, pendingTasks, successRate);
        }

        @Override
        protected void onStart() {
            log.info("Task Monitor started, tracking task completion statistics");
        }

        @Override
        protected void onStop() {
            log.info("📋 FINAL STATISTICS - Total: {}, Completed: {}, Failed: {}, Success Rate: {:.1f}%",
                    totalTasks, completedTasks, failedTasks,
                    totalTasks > 0 ? (double) completedTasks / totalTasks * 100 : 0);
        }
    }
}
