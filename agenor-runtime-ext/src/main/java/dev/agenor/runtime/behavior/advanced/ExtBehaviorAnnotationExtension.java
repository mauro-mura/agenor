package dev.agenor.runtime.behavior.advanced;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.agenor.core.Agent;
import dev.agenor.core.BehaviorType;
import dev.agenor.core.annotations.Behavior;
import dev.agenor.core.composite.CompletionStrategy;
import dev.agenor.core.spi.BehaviorAnnotationExtension;
import dev.agenor.runtime.behavior.composite.FSMBehavior;
import dev.agenor.runtime.behavior.composite.ParallelBehavior;
import dev.agenor.runtime.behavior.composite.SequentialBehavior;

/**
 * {@link BehaviorAnnotationExtension} implementation covering the {@code @Behavior}
 * types whose implementation classes live in {@code agenor-runtime-ext}: CONDITIONAL,
 * THROTTLED, BATCH, RETRY, SEQUENTIAL, PARALLEL, FSM. Discovered via
 * {@code META-INF/services} by {@code agenor-runtime}'s core annotation processor.
 *
 * @since 0.25.0
 */
public final class ExtBehaviorAnnotationExtension implements BehaviorAnnotationExtension {

    private static final Logger log = LoggerFactory.getLogger(ExtBehaviorAnnotationExtension.class);

    private static final Set<BehaviorType> SUPPORTED = EnumSet.of(
            BehaviorType.CONDITIONAL, BehaviorType.THROTTLED, BehaviorType.BATCH,
            BehaviorType.RETRY, BehaviorType.SEQUENTIAL, BehaviorType.PARALLEL, BehaviorType.FSM);

    @Override
    public boolean supports(BehaviorType type) {
        return SUPPORTED.contains(type);
    }

    @Override
    public dev.agenor.core.Behavior createBehavior(Agent agent, Method method, Behavior annotation) {
        return switch (annotation.type()) {
            case CONDITIONAL -> createConditionalBehavior(agent, method, annotation);
            case THROTTLED -> createThrottledBehavior(agent, method, annotation);
            case BATCH -> createBatchBehavior(agent, method, annotation);
            case RETRY -> createRetryBehavior(agent, method, annotation);
            case SEQUENTIAL -> createSequentialBehavior(agent, method, annotation);
            case PARALLEL -> createParallelBehavior(agent, method, annotation);
            case FSM -> createFSMBehavior(agent, method, annotation);
            default -> throw new IllegalStateException("Unsupported behavior type: " + annotation.type());
        };
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

        return new ConditionalBehavior(behaviorId, condition, interval) {
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

        return new ThrottledBehavior(behaviorId, rateLimit, interval, true) {
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
        return new BatchBehavior<Object>(behaviorId, finalBatchSize, maxWaitTime) {
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
        RetryBehavior.BackoffStrategy backoffStrategy;
        try {
            backoffStrategy = RetryBehavior.BackoffStrategy.valueOf(backoffStr);
        } catch (IllegalArgumentException e) {
            log.warn("Invalid backoff strategy '{}', using EXPONENTIAL", backoffStr);
            backoffStrategy = RetryBehavior.BackoffStrategy.EXPONENTIAL;
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
        return new RetryBehavior<Object>(
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
}
