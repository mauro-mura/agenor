package dev.agenor.runtime.behavior.composite;

import dev.agenor.core.Behavior;
import dev.agenor.core.BehaviorType;
import dev.agenor.core.composite.CompositeBehavior;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;

/**
 * Finite State Machine behavior with state transitions.
 * Executes behaviors associated with states and transitions based on conditions.
 */
public class FSMBehavior extends CompositeBehavior {

    private static final Logger log = LoggerFactory.getLogger(FSMBehavior.class);

    private final Map<String, State> states;
    private final Map<String, List<Transition>> transitions;
    private String currentState;
    private String initialState;
    private Duration stateTimeout; // Timeout for state execution

    public FSMBehavior(String behaviorId, String initialState) {
        this(behaviorId, initialState, null);
    }

    public FSMBehavior(String behaviorId, String initialState, Duration stateTimeout) {
        super(behaviorId);
        this.states = new HashMap<>();
        this.transitions = new HashMap<>();
        this.currentState = initialState;
        this.initialState = initialState;
        this.stateTimeout = stateTimeout;
    }

    @Override
    public BehaviorType getType() {
        return BehaviorType.FSM;
    }

    @Override
    public CompletableFuture<Void> execute() {
        if (!active) {
            return CompletableFuture.completedFuture(null);
        }

        State state = states.get(currentState);
        if (state == null) {
            log.error("FSM '{}': Current state '{}' not found", behaviorId, currentState);
            active = false;
            return CompletableFuture.failedFuture(
                new IllegalStateException("State not found: " + currentState)
            );
        }

        log.trace("FSM '{}' executing state: {}", behaviorId, currentState);

        // Execute state behavior with optional timeout
        CompletableFuture<Void> stateFuture = state.execute();

        if (stateTimeout != null) {
            stateFuture = stateFuture.orTimeout(stateTimeout.toMillis(),
                            java.util.concurrent.TimeUnit.MILLISECONDS)
                    .exceptionally(throwable -> {
                        if (throwable.getCause() instanceof java.util.concurrent.TimeoutException) {
                            log.warn("FSM '{}' state '{}' timed out after {}",
                                    behaviorId, currentState, stateTimeout);
                        }
                        throw new java.util.concurrent.CompletionException(throwable);
                    });
        }

        return stateFuture
                .thenCompose(v -> evaluateTransitions())
                .exceptionally(throwable -> {
                    log.error("FSM '{}' error in state '{}': {}",
                            behaviorId, currentState, throwable.getMessage());
                    return null;
                });
    }

    /**
     * Evaluate transitions from current state
     */
    private CompletableFuture<Void> evaluateTransitions() {
        List<Transition> stateTransitions = transitions.get(currentState);

        if (stateTransitions == null || stateTransitions.isEmpty()) {
            // No transitions, FSM stays in current state
            return CompletableFuture.completedFuture(null);
        }

        // Find first matching transition
        for (Transition transition : stateTransitions) {
            if (transition.condition.test(this)) {
                String previousState = currentState;
                currentState = transition.targetState;

                log.debug("FSM '{}' transition: {} -> {} ({})",
                         behaviorId, previousState, currentState, transition.name);

                // Execute transition action if present
                if (transition.action != null) {
                    return transition.action.execute()
                        .exceptionally(throwable -> {
                            log.warn("FSM '{}' transition action failed: {}",
                                    behaviorId, throwable.getMessage());
                            return null;
                        });
                }
                break;
            }
        }

        return CompletableFuture.completedFuture(null);
    }

    /**
     * Add a state to the FSM
     */
    public FSMBehavior addState(String stateName, Behavior behavior) {
        State state = new State(stateName, behavior);
        states.put(stateName, state);
        log.debug("FSM '{}' added state: {}", behaviorId, stateName);
        return this;
    }

    /**
     * Add a transition between states
     */
    public FSMBehavior addTransition(String fromState, String toState,
                                     Predicate<FSMBehavior> condition) {
        return addTransition(fromState, toState, condition, null, null);
    }

    /**
     * Add a transition with an action to execute during transition
     */
    public FSMBehavior addTransition(String fromState, String toState,
                                     Predicate<FSMBehavior> condition,
                                     String transitionName,
                                     Behavior transitionAction) {
        Transition transition = new Transition(
            transitionName != null ? transitionName : fromState + "->" + toState,
            toState,
            condition,
            transitionAction
        );

        transitions.computeIfAbsent(fromState, k -> new ArrayList<>()).add(transition);

        log.debug("FSM '{}' added transition: {} -> {}", behaviorId, fromState, toState);
        return this;
    }

    /**
     * Reset FSM to initial state
     */
    public void reset() {
        currentState = initialState;
        log.debug("FSM '{}' reset to initial state: {}", behaviorId, initialState);
    }

    /**
     * Get current state name
     */
    public String getCurrentState() {
        return currentState;
    }

    /**
     * Manually transition to a state (force transition)
     */
    public void transitionTo(String stateName) {
        if (!states.containsKey(stateName)) {
            log.warn("FSM '{}' cannot transition to unknown state: {}", behaviorId, stateName);
            return;
        }

        String previousState = currentState;
        currentState = stateName;
        log.info("FSM '{}' forced transition: {} -> {}", behaviorId, previousState, currentState);
    }

    /**
     * Check if FSM is in a specific state
     */
    public boolean isInState(String stateName) {
        return currentState.equals(stateName);
    }

    /**
     * Get all state names
     */
    public Set<String> getStateNames() {
        return Collections.unmodifiableSet(states.keySet());
    }

    /**
     * Set timeout for state execution
     */
    public void setStateTimeout(Duration timeout) {
        this.stateTimeout = timeout;
        log.debug("FSM '{}' state timeout set to: {}", behaviorId, timeout);
    }

    /**
     * Get configured state timeout
     */
    public Duration getStateTimeout() {
        return stateTimeout;
    }

    // Inner classes

    private static class State {
        final String name;
        final Behavior behavior;

        State(String name, Behavior behavior) {
            this.name = name;
            this.behavior = behavior;
        }

        CompletableFuture<Void> execute() {
            return behavior.execute();
        }
    }

    private static class Transition {
        final String name;
        final String targetState;
        final Predicate<FSMBehavior> condition;
        final Behavior action;

        Transition(String name, String targetState,
                   Predicate<FSMBehavior> condition, Behavior action) {
            this.name = name;
            this.targetState = targetState;
            this.condition = condition;
            this.action = action;
        }
    }

    /**
     * Builder for FSM construction
     */
    public static class Builder {
        private final String behaviorId;
        private final String initialState;
        private final FSMBehavior fsm;

        public Builder(String behaviorId, String initialState) {
            this(behaviorId, initialState, null);
        }

        public Builder(String behaviorId, String initialState, Duration stateTimeout) {
            this.behaviorId = behaviorId;
            this.initialState = initialState;
            this.fsm = new FSMBehavior(behaviorId, initialState, stateTimeout);
        }

        public Builder state(String name, Behavior behavior) {
            fsm.addState(name, behavior);
            return this;
        }

        public Builder transition(String from, String to, Predicate<FSMBehavior> condition) {
            fsm.addTransition(from, to, condition);
            return this;
        }

        public Builder transition(String from, String to, Predicate<FSMBehavior> condition,
                                String name, Behavior action) {
            fsm.addTransition(from, to, condition, name, action);
            return this;
        }

        public FSMBehavior build() {
            if (!fsm.states.containsKey(initialState)) {
                throw new IllegalStateException(
                    "Initial state '" + initialState + "' not defined in FSM"
                );
            }
            return fsm;
        }

        public Builder stateTimeout(Duration timeout) {
            fsm.stateTimeout = timeout;
            return this;
        }
    }


    public static Builder builder(String behaviorId, String initialState) {
        return new Builder(behaviorId, initialState);
    }

    public static Builder builder(String behaviorId, String initialState, Duration stateTimeout) {
        return new Builder(behaviorId, initialState, stateTimeout);
    }
}
