package dev.agenor.runtime.behavior.advanced;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.agenor.core.BehaviorType;
import dev.agenor.runtime.behavior.BaseBehavior;

/**
 * CircuitBreakerBehavior - Advanced behavior implementing the Circuit Breaker pattern
 * for fault tolerance and cascading failure prevention.
 *
 * <p>The circuit breaker has three states:
 * <ul>
 *   <li><b>CLOSED</b>: Normal operation, requests are allowed</li>
 *   <li><b>OPEN</b>: Failure threshold exceeded, requests are blocked</li>
 *   <li><b>HALF_OPEN</b>: Testing if service recovered, limited requests allowed</li>
 * </ul>
 *
 * <p>State Transitions:
 * <pre>
 *     CLOSED --[failures >= threshold]--> OPEN
 *     OPEN --[recovery timeout elapsed]--> HALF_OPEN
 *     HALF_OPEN --[success >= success threshold]--> CLOSED
 *     HALF_OPEN --[any failure]--> OPEN
 * </pre>
 *
 * <p>Features:
 * <ul>
 *   <li>Configurable failure threshold</li>
 *   <li>Automatic recovery timeout</li>
 *   <li>Success threshold for HALF_OPEN → CLOSED transition</li>
 *   <li>Comprehensive metrics tracking</li>
 *   <li>State change callbacks</li>
 *   <li>Thread-safe state management</li>
 *   <li>Fallback mechanism support</li>
 * </ul>
 *
 * <p>Thread Safety: This class is thread-safe and can be used in concurrent environments.
 *
 * <p>Example Usage:
 * <pre>{@code
 * CircuitBreakerBehavior<String> breaker = new CircuitBreakerBehavior<>(
 *     "api-breaker",
 *     5,                          // Open after 5 failures
 *     Duration.ofSeconds(30),     // Try recovery after 30s
 *     3                           // Close after 3 successes in HALF_OPEN
 * ) {
 *     @Override
 *     protected String executeAction() throws Exception {
 *         return externalService.call();
 *     }
 *
 *     @Override
 *     protected String fallback(Exception e) {
 *         return "Service temporarily unavailable";
 *     }
 * };
 *
 * breaker.onStateChange(state ->
 *     log.warn("Circuit breaker state changed to: {}", state)
 * );
 * }</pre>
 *
 * @param <T> the type of result produced by the protected action
 *
 * @since 0.2.0
 */
public abstract class CircuitBreakerBehavior<T> extends BaseBehavior {

    private static final Logger log = LoggerFactory.getLogger(CircuitBreakerBehavior.class);

    /**
     * Circuit breaker states
     */
    public enum State {
        /**
         * Normal operation - requests are allowed
         */
        CLOSED,

        /**
         * Circuit is open - requests are blocked, failures returned immediately
         */
        OPEN,

        /**
         * Testing recovery - limited requests allowed to test if service recovered
         */
        HALF_OPEN
    }

    // Configuration
    private final int failureThreshold;
    private final Duration recoveryTimeout;
    private final int successThreshold;

    // State management
    private final AtomicReference<State> currentState;
    private final AtomicInteger consecutiveFailures;
    private final AtomicInteger consecutiveSuccesses;
    private final AtomicReference<Instant> stateChangedAt;
    private final AtomicReference<Instant> lastFailureTime;

    // Metrics
    private final AtomicLong totalRequests;
    private final AtomicLong successfulRequests;
    private final AtomicLong failedRequests;
    private final AtomicLong rejectedRequests;
    private final AtomicInteger stateChangeCount;

    // Callbacks
    private volatile Consumer<State> stateChangeListener;
    private volatile Consumer<T> successListener;
    private volatile Consumer<Exception> failureListener;

    /**
     * Creates a CircuitBreakerBehavior with default success threshold.
     *
     * @param behaviorId unique identifier for this behavior
     * @param failureThreshold number of consecutive failures before opening circuit
     * @param recoveryTimeout duration to wait in OPEN state before transitioning to HALF_OPEN
     */
    public CircuitBreakerBehavior(String behaviorId,
                                 int failureThreshold,
                                 Duration recoveryTimeout) {
        this(behaviorId, failureThreshold, recoveryTimeout, 1);
    }

    /**
     * Creates a CircuitBreakerBehavior with full configuration.
     *
     * @param behaviorId unique identifier for this behavior
     * @param failureThreshold number of consecutive failures before opening circuit
     * @param recoveryTimeout duration to wait in OPEN state before transitioning to HALF_OPEN
     * @param successThreshold number of consecutive successes in HALF_OPEN to close circuit
     */
    public CircuitBreakerBehavior(String behaviorId,
                                 int failureThreshold,
                                 Duration recoveryTimeout,
                                 int successThreshold) {
        super(behaviorId, BehaviorType.CIRCUIT_BREAKER, null);

        if (failureThreshold <= 0) {
            throw new IllegalArgumentException("Failure threshold must be positive");
        }
        if (recoveryTimeout == null || recoveryTimeout.isNegative()) {
            throw new IllegalArgumentException("Recovery timeout must be positive");
        }
        if (successThreshold <= 0) {
            throw new IllegalArgumentException("Success threshold must be positive");
        }

        this.failureThreshold = failureThreshold;
        this.recoveryTimeout = recoveryTimeout;
        this.successThreshold = successThreshold;

        // Initialize state
        this.currentState = new AtomicReference<>(State.CLOSED);
        this.consecutiveFailures = new AtomicInteger(0);
        this.consecutiveSuccesses = new AtomicInteger(0);
        this.stateChangedAt = new AtomicReference<>(Instant.now());
        this.lastFailureTime = new AtomicReference<>();

        // Initialize metrics
        this.totalRequests = new AtomicLong(0);
        this.successfulRequests = new AtomicLong(0);
        this.failedRequests = new AtomicLong(0);
        this.rejectedRequests = new AtomicLong(0);
        this.stateChangeCount = new AtomicInteger(0);

        log.info("CircuitBreaker '{}' created: failureThreshold={}, recoveryTimeout={}, successThreshold={}",
                behaviorId, failureThreshold, recoveryTimeout, successThreshold);
    }

    /**
     * Implementation of BaseBehavior's action() method.
     * Executes the protected action through the circuit breaker.
     */
    @Override
    protected void action() {
        try {
            call();
        } catch (CircuitBreakerOpenException e) {
            // Circuit is open, this is expected behavior
            log.trace("CircuitBreaker '{}' is OPEN, skipping execution", getBehaviorId());
        } catch (Exception e) {
            // Exception already handled by call() -> onFailure()
            log.trace("CircuitBreaker '{}' action failed (already recorded)", getBehaviorId());
        }
    }

    /**
     * Attempts to execute the protected action through the circuit breaker.
     *
     * @return the result of the action
     * @throws CircuitBreakerOpenException if circuit is open
     * @throws Exception if the action fails
     */
    public T call() throws Exception {
        totalRequests.incrementAndGet();

        // Check if we should transition from OPEN to HALF_OPEN
        if (currentState.get() == State.OPEN) {
            if (shouldAttemptRecovery()) {
                transitionToHalfOpen();
            } else {
                rejectedRequests.incrementAndGet();
                throw new CircuitBreakerOpenException(
                    String.format("Circuit breaker '%s' is OPEN", getBehaviorId())
                );
            }
        }

        // Execute the action
        try {
            T result = executeAction();
            onSuccess(result);
            return result;
        } catch (Exception e) {
            onFailure(e);
            throw e;
        }
    }

    /**
     * Executes the protected action. This method must be implemented by subclasses.
     *
     * @return the result of the action
     * @throws Exception if the action fails
     */
    protected abstract T executeAction() throws Exception;

    /**
     * Provides a fallback result when circuit is open.
     * Default implementation returns null.
     *
     * @param e the exception that caused the circuit to open
     * @return a fallback result
     */
    protected T fallback(Exception e) {
        return null;
    }

    /**
     * Handles successful execution.
     */
    private void onSuccess(T result) {
        successfulRequests.incrementAndGet();
        consecutiveFailures.set(0);

        State state = currentState.get();

        // Notify success listener
        try {
            if (successListener != null) {
                successListener.accept(result);
            }
        } catch (Exception listenerException) {
            log.error("Error in success listener for CircuitBreaker '{}'", getBehaviorId(), listenerException);
        }

        if (state == State.HALF_OPEN) {
            int successes = consecutiveSuccesses.incrementAndGet();
            log.debug("CircuitBreaker '{}' success in HALF_OPEN: {}/{}",
                     getBehaviorId(), successes, successThreshold);

            if (successes >= successThreshold) {
                transitionToClosed();
            }
        } else if (state == State.CLOSED) {
            consecutiveSuccesses.set(0); // Reset in CLOSED state
        }
    }

    /**
     * Handles failed execution.
     */
    private void onFailure(Exception e) {
        failedRequests.incrementAndGet();
        lastFailureTime.set(Instant.now());
        consecutiveSuccesses.set(0);

        State state = currentState.get();
        int failures = consecutiveFailures.incrementAndGet();

        log.debug("CircuitBreaker '{}' failure in {} state: {}/{}",
                 getBehaviorId(), state, failures, failureThreshold);

        // Notify failure listener
        try {
            if (failureListener != null) {
                failureListener.accept(e);
            }
        } catch (Exception listenerException) {
            log.error("Error in failure listener for CircuitBreaker '{}'", getBehaviorId(), listenerException);
        }

        if (state == State.HALF_OPEN) {
            // Any failure in HALF_OPEN immediately opens the circuit
            transitionToOpen();
        } else if (state == State.CLOSED && failures >= failureThreshold) {
            // Threshold reached in CLOSED state, open the circuit
            transitionToOpen();
        }
    }

    /**
     * Checks if enough time has elapsed to attempt recovery.
     */
    private boolean shouldAttemptRecovery() {
        Instant stateChanged = stateChangedAt.get();
        return Instant.now().isAfter(stateChanged.plus(recoveryTimeout));
    }

    /**
     * Transitions to CLOSED state.
     */
    private void transitionToClosed() {
        State oldState = currentState.getAndSet(State.CLOSED);
        if (oldState != State.CLOSED) {
            stateChangedAt.set(Instant.now());
            consecutiveFailures.set(0);
            consecutiveSuccesses.set(0);
            stateChangeCount.incrementAndGet();

            log.info("CircuitBreaker '{}' transitioned: {} → CLOSED", getBehaviorId(), oldState);
            notifyStateChange(State.CLOSED);
        }
    }

    /**
     * Transitions to OPEN state.
     */
    private void transitionToOpen() {
        State oldState = currentState.getAndSet(State.OPEN);
        if (oldState != State.OPEN) {
            stateChangedAt.set(Instant.now());
            consecutiveSuccesses.set(0);
            stateChangeCount.incrementAndGet();

            log.warn("CircuitBreaker '{}' transitioned: {} → OPEN (failures: {})",
                    getBehaviorId(), oldState, consecutiveFailures.get());
            notifyStateChange(State.OPEN);
        }
    }

    /**
     * Transitions to HALF_OPEN state.
     */
    private void transitionToHalfOpen() {
        State oldState = currentState.getAndSet(State.HALF_OPEN);
        if (oldState != State.HALF_OPEN) {
            stateChangedAt.set(Instant.now());
            consecutiveSuccesses.set(0);
            stateChangeCount.incrementAndGet();

            log.info("CircuitBreaker '{}' transitioned: {} → HALF_OPEN (attempting recovery)",
                    getBehaviorId(), oldState);
            notifyStateChange(State.HALF_OPEN);
        }
    }

    /**
     * Notifies state change listener if configured.
     */
    private void notifyStateChange(State newState) {
        try {
            if (stateChangeListener != null) {
                stateChangeListener.accept(newState);
            }
        } catch (Exception e) {
            log.error("Error in state change listener for CircuitBreaker '{}'", getBehaviorId(), e);
        }
    }

    // ==================== PUBLIC API ====================

    /**
     * Gets the current circuit breaker state.
     *
     * @return the current state
     */
    public State getCurrentState() {
        return currentState.get();
    }

    /**
     * Checks if the circuit is closed (accepting requests).
     *
     * @return true if circuit is closed
     */
    public boolean isClosed() {
        return currentState.get() == State.CLOSED;
    }

    /**
     * Checks if the circuit is open (rejecting requests).
     *
     * @return true if circuit is open
     */
    public boolean isOpen() {
        return currentState.get() == State.OPEN;
    }

    /**
     * Checks if the circuit is half-open (testing recovery).
     *
     * @return true if circuit is half-open
     */
    public boolean isHalfOpen() {
        return currentState.get() == State.HALF_OPEN;
    }

    /**
     * Manually resets the circuit breaker to CLOSED state.
     * This should be used with caution, typically only for testing or administrative purposes.
     */
    public void reset() {
        consecutiveFailures.set(0);
        consecutiveSuccesses.set(0);
        transitionToClosed();
        log.info("CircuitBreaker '{}' manually reset", getBehaviorId());
    }

    /**
     * Manually trips the circuit breaker to OPEN state.
     * Useful for testing or emergency scenarios.
     */
    public void trip() {
        consecutiveFailures.set(failureThreshold);
        transitionToOpen();
        log.warn("CircuitBreaker '{}' manually tripped", getBehaviorId());
    }

    // ==================== CALLBACKS ====================

    /**
     * Sets a callback to be invoked when the circuit breaker state changes.
     *
     * @param listener the state change listener
     */
    public void onStateChange(Consumer<State> listener) {
        this.stateChangeListener = listener;
    }

    /**
     * Sets a callback to be invoked on successful executions.
     *
     * @param listener the success listener
     */
    public void onSuccess(Consumer<T> listener) {
        this.successListener = listener;
    }

    /**
     * Sets a callback to be invoked on failed executions.
     *
     * @param listener the failure listener
     */
    public void onFailure(Consumer<Exception> listener) {
        this.failureListener = listener;
    }

    // ==================== METRICS ====================

    /**
     * Gets comprehensive metrics for this circuit breaker.
     *
     * @return circuit breaker metrics
     */
    public CircuitBreakerMetrics getMetrics() {
        return new CircuitBreakerMetrics(
            getBehaviorId(),
            currentState.get(),
            totalRequests.get(),
            successfulRequests.get(),
            failedRequests.get(),
            rejectedRequests.get(),
            consecutiveFailures.get(),
            consecutiveSuccesses.get(),
            stateChangeCount.get(),
            stateChangedAt.get(),
            lastFailureTime.get()
        );
    }

    /**
     * Resets all metrics counters to zero.
     */
    public void resetMetrics() {
        totalRequests.set(0);
        successfulRequests.set(0);
        failedRequests.set(0);
        rejectedRequests.set(0);
        // Don't reset state change count - it's valuable historical data
        log.debug("CircuitBreaker '{}' metrics reset", getBehaviorId());
    }

    // ==================== FACTORY METHODS ====================

    /**
     * Creates a CircuitBreaker with standard configuration.
     *
     * @param behaviorId unique identifier
     * @param actionSupplier the action to protect
     * @param <R> result type
     * @return configured circuit breaker
     */
    public static <R> CircuitBreakerBehavior<R> standard(
            String behaviorId,
            ThrowingSupplier<R> actionSupplier) {
        return new CircuitBreakerBehavior<>(
                behaviorId,
                5,  // 5 failures
                Duration.ofSeconds(30),  // 30s recovery
                3   // 3 successes to close
        ) {
            @Override
            protected R executeAction() throws Exception {
                return actionSupplier.get();
            }
        };
    }

    /**
     * Creates a CircuitBreaker with custom configuration.
     *
     * @param behaviorId unique identifier
     * @param actionSupplier the action to protect
     * @param failureThreshold failures before opening
     * @param recoveryTimeout wait time in OPEN state
     * @param successThreshold successes to close from HALF_OPEN
     * @param <R> result type
     * @return configured circuit breaker
     */
    public static <R> CircuitBreakerBehavior<R> custom(
            String behaviorId,
            ThrowingSupplier<R> actionSupplier,
            int failureThreshold,
            Duration recoveryTimeout,
            int successThreshold) {
        return new CircuitBreakerBehavior<>(
                behaviorId,
                failureThreshold,
                recoveryTimeout,
                successThreshold
        ) {
            @Override
            protected R executeAction() throws Exception {
                return actionSupplier.get();
            }
        };
    }

    // ==================== HELPER CLASSES ====================

    /**
     * Functional interface for actions that may throw exceptions.
     */
    @FunctionalInterface
    public interface ThrowingSupplier<T> {
        T get() throws Exception;
    }

    /**
     * Exception thrown when circuit breaker is open.
     */
    public static class CircuitBreakerOpenException extends Exception {
        public CircuitBreakerOpenException(String message) {
            super(message);
        }
    }

    /**
     * Immutable metrics snapshot for circuit breaker state.
     */
    public record CircuitBreakerMetrics(
        String behaviorId,
        State currentState,
        long totalRequests,
        long successfulRequests,
        long failedRequests,
        long rejectedRequests,
        int consecutiveFailures,
        int consecutiveSuccesses,
        int stateChangeCount,
        Instant stateChangedAt,
        Instant lastFailureTime
    ) {
        /**
         * Calculates the success rate as a percentage.
         *
         * @return success rate (0-100)
         */
        public double successRate() {
            if (totalRequests == 0) return 0.0;
            return (successfulRequests * 100.0) / totalRequests;
        }

        /**
         * Calculates the failure rate as a percentage.
         *
         * @return failure rate (0-100)
         */
        public double failureRate() {
            if (totalRequests == 0) return 0.0;
            return (failedRequests * 100.0) / totalRequests;
        }

        /**
         * Gets duration since last state change.
         *
         * @return duration in current state
         */
        public Duration timeInCurrentState() {
            return Duration.between(stateChangedAt, Instant.now());
        }

        @Override
        public String toString() {
            return String.format(
                "CircuitBreakerMetrics[id=%s, state=%s, requests=%d, success=%d (%.1f%%), " +
                "failed=%d (%.1f%%), rejected=%d, stateChanges=%d, timeInState=%s]",
                behaviorId, currentState, totalRequests,
                successfulRequests, successRate(),
                failedRequests, failureRate(),
                rejectedRequests, stateChangeCount,
                timeInCurrentState()
            );
        }
    }
}
