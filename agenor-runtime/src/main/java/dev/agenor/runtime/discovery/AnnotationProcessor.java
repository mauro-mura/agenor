package dev.agenor.runtime.discovery;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.time.Duration;
import java.util.List;

import dev.agenor.core.annotations.AgenorMessageHandler;
import dev.agenor.core.annotations.Behavior;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.agenor.core.Agent;
import dev.agenor.core.Message;
import dev.agenor.core.MessageHandler;
import dev.agenor.core.messaging.TopicSubscriber;
import dev.agenor.core.composite.CompletionStrategy;
import dev.agenor.runtime.behavior.BaseBehavior;
import dev.agenor.runtime.behavior.CyclicBehavior;
import dev.agenor.runtime.behavior.EventDrivenBehavior;
import dev.agenor.runtime.behavior.OneShotBehavior;
import dev.agenor.runtime.behavior.WakerBehavior;
import dev.agenor.runtime.behavior.composite.FSMBehavior;
import dev.agenor.runtime.behavior.composite.ParallelBehavior;
import dev.agenor.runtime.behavior.composite.SequentialBehavior;

/**
 * Processor for handling annotations on agent classes.
 * Creates behaviors and message handlers from annotations.
 */
public class AnnotationProcessor {

    private static final Logger log = LoggerFactory.getLogger(AnnotationProcessor.class);

    private final TopicSubscriber topicSubscriber;

    public AnnotationProcessor(TopicSubscriber topicSubscriber) {
        this.topicSubscriber = topicSubscriber;
    }

    /**
     * Process all annotations on an agent
     */
    public void processAnnotations(Agent agent) {
        Class<?> agentClass = agent.getClass();

        log.debug("Processing annotations for agent: {} ({})", agent.getAgentName(), agentClass.getName());

        // Process behavior annotations
        processBehaviorAnnotations(agent, agentClass);

        // Process message handler annotations
        processMessageHandlerAnnotations(agent, agentClass);

        log.debug("Annotation processing completed for agent: {}", agent.getAgentName());
    }

    /**
     * Process @Behavior annotations
     */
    private void processBehaviorAnnotations(Agent agent, Class<?> agentClass) {
        Method[] methods = agentClass.getDeclaredMethods();

        for (Method method : methods) {
            Behavior behaviorAnnotation = method.getAnnotation(Behavior.class);

            if (behaviorAnnotation != null) {
                try {
                    createBehaviorFromAnnotation(agent, method, behaviorAnnotation);
                } catch (Exception e) {
                    log.error("Failed to create behavior from annotation for method: {}.{}",
                             agentClass.getName(), method.getName(), e);
                }
            }
        }
    }

    /**
     * Process @AgenorMessageHandler annotations
     */
    private void processMessageHandlerAnnotations(Agent agent, Class<?> agentClass) {
        Method[] methods = agentClass.getDeclaredMethods();

        for (Method method : methods) {
            AgenorMessageHandler handlerAnnotation = method.getAnnotation(AgenorMessageHandler.class);

            if (handlerAnnotation != null && handlerAnnotation.autoSubscribe()) {
                try {
                    createMessageHandlerFromAnnotation(agent, method, handlerAnnotation);
                } catch (Exception e) {
                    log.error("Failed to create message handler from annotation for method: {}.{}",
                             agentClass.getName(), method.getName(), e);
                }
            }
        }
    }

    /**
     * Create behavior from @Behavior annotation
     */
    private void createBehaviorFromAnnotation(Agent agent, Method method, Behavior annotation) {
        if (!annotation.autoStart()) {
            log.debug("Skipping auto-start for behavior method: {}", method.getName());
            return;
        }

        // Validate method signature
        if (!isValidBehaviorMethod(method)) {
            log.warn("Invalid behavior method signature: {}. Method should be public, non-static, and have no parameters",
                    method.getName());
            return;
        }

        method.setAccessible(true);

        dev.agenor.core.Behavior behavior = switch (annotation.type()) {
            case ONE_SHOT -> createOneShotBehavior(agent, method, annotation);
            case CYCLIC -> createCyclicBehavior(agent, method, annotation);
            case WAKER -> createWakerBehavior(agent, method, annotation);
            case EVENT_DRIVEN -> createEventDrivenBehavior(agent, method, annotation);
            case CONDITIONAL -> createConditionalBehavior(agent, method, annotation);
            case THROTTLED -> createThrottledBehavior(agent, method, annotation);
            case BATCH -> createBatchBehavior(agent, method, annotation);
            case RETRY -> createRetryBehavior(agent, method, annotation);
            case CUSTOM -> createCustomBehavior(agent, method, annotation);
            case SEQUENTIAL -> createSequentialBehavior(agent, method, annotation);
            case PARALLEL -> createParallelBehavior(agent, method, annotation);
            case FSM -> createFSMBehavior(agent, method, annotation);
            default -> throw new UnsupportedOperationException("Behavior type not supported: " + annotation.type());
        };

        if (behavior != null) {
            if (behavior instanceof BaseBehavior baseBehavior) {
                baseBehavior.setAgent(agent);
            }

            agent.addBehavior(behavior);

            log.info("Added {} behavior '{}' to agent '{}'",
                    annotation.type().name().toLowerCase(),
                    method.getName(),
                    agent.getAgentName());
        }
    }

    private dev.agenor.core.Behavior createSequentialBehavior(Agent agent, Method method, Behavior annotation) {
        String behaviorId = generateBehaviorId(agent, method);
        Duration stepTimeout = annotation.stepTimeout().isEmpty()
                ? null : parseDuration(annotation.stepTimeout());
        // interval present → repeating (CYCLIC hint); absent → one-shot (ONCE hint)
        Duration interval = annotation.interval().isEmpty()
                ? null : parseDuration(annotation.interval());

        SequentialBehavior sequential = interval != null
                ? new SequentialBehavior(behaviorId, interval)
                : new SequentialBehavior(behaviorId);

        if (stepTimeout != null) {
            sequential.withStepTimeout(stepTimeout);
        }

        log.info("Created SEQUENTIAL behavior '{}' (repeating: {}, stepTimeout: {})",
                behaviorId, interval != null, stepTimeout);
        log.warn("SEQUENTIAL behavior '{}' has no child behaviors. " +
                "Add them programmatically using addChildBehavior()", behaviorId);
        return sequential;
    }

    private dev.agenor.core.Behavior createParallelBehavior(Agent agent, Method method, Behavior annotation) {
        String behaviorId = generateBehaviorId(agent, method);
        String strategyStr = annotation.parallelStrategy().toUpperCase();

        CompletionStrategy strategy;
        try {
            strategy = CompletionStrategy.valueOf(strategyStr);
        } catch (IllegalArgumentException e) {
            log.warn("Invalid parallel strategy '{}', using ALL", strategyStr);
            strategy = CompletionStrategy.ALL;
        }

        int required = annotation.requiredCompletions();
        Duration childTimeout = annotation.childTimeout().isEmpty() ? null : parseDuration(annotation.childTimeout());

        ParallelBehavior parallel = new ParallelBehavior(behaviorId, strategy, required, childTimeout);

        log.info("Created PARALLEL behavior '{}' (strategy: {}, required: {}, childTimeout: {})",
                behaviorId, strategy, required, childTimeout);
        log.warn("PARALLEL behavior '{}' has no child behaviors. Add them programmatically using addChildBehavior()", behaviorId);

        // Note: Child behaviors should be added programmatically
        // Example in agent code:
        //   parallel.addChildBehavior(new OneShotBehavior(...));
        //   parallel.addChildBehavior(new OneShotBehavior(...));

        return parallel;
    }

    private dev.agenor.core.Behavior createFSMBehavior(Agent agent, Method method, Behavior annotation) {
        String behaviorId = generateBehaviorId(agent, method);
        String initialState = annotation.fsmInitialState();
        Duration stateTimeout = annotation.stateTimeout().isEmpty() ? null : parseDuration(annotation.stateTimeout());

        if (initialState.isEmpty()) {
            initialState = "START";
        }

        FSMBehavior fsm = new FSMBehavior(behaviorId, initialState, stateTimeout);

        log.info("Created FSM behavior '{}' (initial state: {}, stateTimeout: {})",
                behaviorId, initialState, stateTimeout);
        log.warn("FSM behavior '{}' has no states or transitions. Build it programmatically using FSMBehavior.builder()", behaviorId);

        // Note: FSM is too complex for annotation-only definition
        // Best created programmatically using the builder:
        // Example:
        //   FSMBehavior.builder("my-fsm", "IDLE")
        //     .state("IDLE", idleBehavior)
        //     .state("ACTIVE", activeBehavior)
        //     .transition("IDLE", "ACTIVE", fsm -> someCondition())
        //     .transition("ACTIVE", "IDLE", fsm -> otherCondition())
        //     .build();

        return fsm;
    }

    private dev.agenor.core.Behavior createConditionalBehavior(Agent agent, Method method, Behavior annotation) {
        String conditionExpr = annotation.condition();
        if (conditionExpr.isEmpty()) {
            log.warn("CONDITIONAL behavior requires 'condition' parameter: {}", method.getName());
            return null;
        }

        // Parse condition expression
        dev.agenor.core.condition.Condition condition = parseCondition(conditionExpr);
        Duration interval = annotation.interval().isEmpty() ? null : parseDuration(annotation.interval());

        String behaviorId = generateBehaviorId(agent, method);

        return new dev.agenor.runtime.behavior.advanced.ConditionalBehavior(behaviorId, condition, interval) {
            @Override
            protected void conditionalAction() {
                invokeMethod(agent, method);
            }
        };
    }

    private dev.agenor.core.Behavior createThrottledBehavior(Agent agent, Method method, Behavior annotation) {
        String rateLimitSpec = annotation.rateLimit();
        if (rateLimitSpec.isEmpty()) {
            log.warn("THROTTLED behavior requires 'rateLimit' parameter: {}", method.getName());
            return null;
        }

        // Parse rate limit
        dev.agenor.core.ratelimit.RateLimit rateLimit =
                dev.agenor.core.ratelimit.RateLimit.parse(rateLimitSpec);
        Duration interval = annotation.interval().isEmpty() ? null : parseDuration(annotation.interval());

        String behaviorId = generateBehaviorId(agent, method);

        return new dev.agenor.runtime.behavior.advanced.ThrottledBehavior(behaviorId, rateLimit, interval, true) {
            @Override
            protected void throttledAction() {
                invokeMethod(agent, method);
            }
        };
    }

    private dev.agenor.core.Behavior createBatchBehavior(Agent agent, Method method, Behavior annotation) {
        String behaviorId = generateBehaviorId(agent, method);
        int batchSize = annotation.batchSize();
        Duration maxWaitTime = annotation.maxWaitTime().isEmpty() ? null : parseDuration(annotation.maxWaitTime());

        if (batchSize <= 0) {
            log.warn("BATCH behavior '{}' has invalid batchSize: {}, using default 10", behaviorId, batchSize);
            batchSize = 10;
        }

        log.info("Created BATCH behavior '{}' (batchSize: {}, maxWaitTime: {})",
                behaviorId, batchSize, maxWaitTime);
        log.warn("BATCH behavior '{}' created via annotation. Use programmatically for full control.", behaviorId);

        // Note: Batch behaviors should typically be created programmatically in onStart()
        // This creates a placeholder that logs a warning
        final int finalBatchSize = batchSize;
        return new dev.agenor.runtime.behavior.advanced.BatchBehavior<Object>(behaviorId, finalBatchSize, maxWaitTime) {
            @Override
            protected void processBatch(List<Object> batch) {
                log.warn("BatchBehavior '{}' processBatch() not implemented - create behavior programmatically", behaviorId);
            }
        };
    }

    private dev.agenor.core.Behavior createRetryBehavior(Agent agent, Method method, Behavior annotation) {
        String behaviorId = generateBehaviorId(agent, method);
        int maxRetries = annotation.maxRetries();
        String backoffStr = annotation.backoff().toUpperCase();
        Duration initialDelay = annotation.initialDelay().isEmpty() ?
                Duration.ofSeconds(1) : parseDuration(annotation.initialDelay());

        // Parse backoff strategy
        dev.agenor.runtime.behavior.advanced.RetryBehavior.BackoffStrategy backoffStrategy;
        try {
            backoffStrategy = dev.agenor.runtime.behavior.advanced.RetryBehavior.BackoffStrategy.valueOf(backoffStr);
        } catch (IllegalArgumentException e) {
            log.warn("Invalid backoff strategy '{}', using EXPONENTIAL", backoffStr);
            backoffStrategy = dev.agenor.runtime.behavior.advanced.RetryBehavior.BackoffStrategy.EXPONENTIAL;
        }

        if (maxRetries < 0) {
            log.warn("RETRY behavior '{}' has invalid maxRetries: {}, using default 3", behaviorId, maxRetries);
            maxRetries = 3;
        }

        log.info("Created RETRY behavior '{}' (maxRetries: {}, backoff: {}, initialDelay: {})",
                behaviorId, maxRetries, backoffStrategy, initialDelay);
        log.warn("RETRY behavior '{}' created via annotation. Use programmatically for full control.", behaviorId);

        // Note: Retry behaviors should typically be created programmatically in onStart()
        // This creates a placeholder that logs a warning
        final int finalMaxRetries = maxRetries;
        return new dev.agenor.runtime.behavior.advanced.RetryBehavior<Object>(
                behaviorId, finalMaxRetries, backoffStrategy, initialDelay) {
            @Override
            protected Object attemptAction() throws Exception {
                invokeMethod(agent, method);
                return null;
            }
        };
    }

    private dev.agenor.core.condition.Condition parseCondition(String expression) {
        // Simple condition parser - supports AND/OR operators
        expression = expression.trim();

        // Handle compound conditions with AND
        if (expression.toLowerCase().contains(" and ")) {
            String[] parts = expression.split("(?i)\\s+and\\s+", 2);
            dev.agenor.core.condition.Condition left = parseCondition(parts[0]);
            dev.agenor.core.condition.Condition right = parseCondition(parts[1]);
            return left.and(right);
        }

        // Handle compound conditions with OR
        if (expression.toLowerCase().contains(" or ")) {
            String[] parts = expression.split("(?i)\\s+or\\s+", 2);
            dev.agenor.core.condition.Condition left = parseCondition(parts[0]);
            dev.agenor.core.condition.Condition right = parseCondition(parts[1]);
            return left.or(right);
        }

        // Parse single condition
        String exprLower = expression.toLowerCase();

        // System conditions
        if (exprLower.matches("system\\.cpu\\s*<\\s*(\\d+(?:\\.\\d+)?)")) {
            double threshold = Double.parseDouble(exprLower.replaceAll(".*<\\s*(\\d+(?:\\.\\d+)?).*", "$1"));
            return dev.agenor.runtime.condition.SystemCondition.cpuBelow(threshold);
        }
        if (exprLower.matches("system\\.memory\\s*<\\s*(\\d+(?:\\.\\d+)?)")) {
            double threshold = Double.parseDouble(exprLower.replaceAll(".*<\\s*(\\d+(?:\\.\\d+)?).*", "$1"));
            return dev.agenor.runtime.condition.SystemCondition.memoryBelow(threshold);
        }
        if (exprLower.equals("system.healthy")) {
            return dev.agenor.runtime.condition.SystemCondition.systemHealthy();
        }
        if (exprLower.equals("system.underload")) {
            return dev.agenor.runtime.condition.SystemCondition.systemUnderLoad();
        }

        // Time conditions
        if (exprLower.equals("time.businesshours")) {
            return dev.agenor.runtime.condition.TimeCondition.businessHours();
        }
        if (exprLower.equals("time.weekday")) {
            return dev.agenor.runtime.condition.TimeCondition.weekday();
        }
        if (exprLower.equals("time.weekend")) {
            return dev.agenor.runtime.condition.TimeCondition.weekend();
        }

        // Agent conditions
        if (exprLower.equals("agent.running")) {
            return dev.agenor.runtime.condition.AgentCondition.isRunning();
        }

        // Default: always true
        log.warn("Unknown condition expression: {}, using always-true", expression);
        return dev.agenor.core.condition.Condition.always();
    }

    /**
     * Create message handler from @AgenorMessageHandler annotation
     */
    private void createMessageHandlerFromAnnotation(Agent agent, Method method, AgenorMessageHandler annotation) {
        // Validate method signature
        if (!isValidMessageHandlerMethod(method)) {
            log.warn("Invalid message handler method signature: {}. Method should be public, non-static, and take a Message parameter",
                    method.getName());
            return;
        }

        method.setAccessible(true);
        String topic = annotation.value();

        MessageHandler handler = MessageHandler.sync(message -> {
            try {
                method.invoke(agent, message);
            } catch (Exception e) {
                throw new RuntimeException("Error executing message handler: " + method.getName(), e);
            }
        });

        String subscriptionId = topicSubscriber.subscribeTopic(topic, handler).subscriptionId();

        log.info("Subscribed agent '{}' to topic '{}' (method: {}, subscription: {})",
                agent.getAgentName(), topic, method.getName(), subscriptionId);
    }

    private dev.agenor.core.Behavior createOneShotBehavior(Agent agent, Method method, Behavior annotation) {
        String behaviorId = generateBehaviorId(agent, method);

        return new OneShotBehavior(behaviorId) {
            @Override
            protected void action() {
                invokeMethod(agent, method);
            }
        };
    }

    private dev.agenor.core.Behavior createCyclicBehavior(Agent agent, Method method, Behavior annotation) {
        String behaviorId = generateBehaviorId(agent, method);
        Duration interval = parseDuration(annotation.interval());

        return new CyclicBehavior(behaviorId, interval) {
            @Override
            protected void action() {
                invokeMethod(agent, method);
            }
        };
    }

    private dev.agenor.core.Behavior createWakerBehavior(Agent agent, Method method, Behavior annotation) {
        String behaviorId = generateBehaviorId(agent, method);
        Duration initialDelay = parseDuration(annotation.initialDelay());

        // For now, treat waker as delayed one-shot
        return WakerBehavior.wakeAfter(initialDelay, () -> invokeMethod(agent, method));
    }

    private dev.agenor.core.Behavior createEventDrivenBehavior(Agent agent, Method method, Behavior annotation) {
        // Event-driven behaviors typically need a topic - for now, use method name as topic
        String topic = method.getName().toLowerCase();
        String behaviorId = generateBehaviorId(agent, method);

        return new EventDrivenBehavior(behaviorId, topic) {
            @Override
            protected void handleMessage(Message message) {
                invokeMethod(agent, method);
            }
        };
    }

    private dev.agenor.core.Behavior createCustomBehavior(Agent agent, Method method, Behavior annotation) {
        // Custom behaviors use the interval as their execution pattern
        String behaviorId = generateBehaviorId(agent, method);
        Duration interval = parseDuration(annotation.interval());

        return new CyclicBehavior(behaviorId, interval) {
            @Override
            protected void action() {
                invokeMethod(agent, method);
            }
        };
    }

    private void invokeMethod(Agent agent, Method method) {
        try {
            method.invoke(agent);
        } catch (Exception e) {
            log.error("Error invoking behavior method: {}.{}",
                     agent.getClass().getName(), method.getName(), e);
        }
    }

    private String generateBehaviorId(Agent agent, Method method) {
        return agent.getAgentId() + "." + method.getName();
    }

    private Duration parseDuration(String durationString) {
        if (durationString == null || durationString.trim().isEmpty()) {
            return Duration.ofSeconds(1); // Default interval
        }

        try {
            durationString = durationString.trim().toLowerCase();

            if (durationString.endsWith("ms")) {
                long value = Long.parseLong(durationString.substring(0, durationString.length() - 2));
                return Duration.ofMillis(value);
            } else if (durationString.endsWith("s")) {
                long value = Long.parseLong(durationString.substring(0, durationString.length() - 1));
                return Duration.ofSeconds(value);
            } else if (durationString.endsWith("m")) {
                long value = Long.parseLong(durationString.substring(0, durationString.length() - 1));
                return Duration.ofMinutes(value);
            } else if (durationString.endsWith("min")) {
                long value = Long.parseLong(durationString.substring(0, durationString.length() - 3));
                return Duration.ofMinutes(value);
            } else if (durationString.endsWith("h")) {
                long value = Long.parseLong(durationString.substring(0, durationString.length() - 1));
                return Duration.ofHours(value);
            } else {
                // Try to parse as seconds
                long value = Long.parseLong(durationString);
                return Duration.ofSeconds(value);
            }
        } catch (NumberFormatException e) {
            log.warn("Invalid duration format: '{}', using 1 second default", durationString);
            return Duration.ofSeconds(1);
        }
    }

    private boolean isValidBehaviorMethod(Method method) {
        return Modifier.isPublic(method.getModifiers()) &&
               !Modifier.isStatic(method.getModifiers()) &&
               method.getParameterCount() == 0 &&
               (method.getReturnType() == void.class || method.getReturnType() == Void.class);
    }

    private boolean isValidMessageHandlerMethod(Method method) {
        return Modifier.isPublic(method.getModifiers()) &&
               !Modifier.isStatic(method.getModifiers()) &&
               method.getParameterCount() == 1 &&
               method.getParameterTypes()[0] == Message.class &&
               (method.getReturnType() == void.class || method.getReturnType() == Void.class);
    }
}
