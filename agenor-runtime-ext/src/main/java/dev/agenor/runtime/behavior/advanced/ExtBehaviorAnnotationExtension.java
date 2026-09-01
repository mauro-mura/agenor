package dev.agenor.runtime.behavior.advanced;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.EnumSet;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.agenor.core.Agent;
import dev.agenor.core.BehaviorType;
import dev.agenor.core.annotations.Behavior;
import dev.agenor.core.spi.BehaviorAnnotationExtension;
import dev.agenor.runtime.behavior.composite.FSMBehavior;

/**
 * {@link BehaviorAnnotationExtension} implementation covering the {@code @Behavior} types
 * whose implementation classes live in {@code agenor-runtime-ext}. Discovered via
 * {@code META-INF/services} by {@code agenor-runtime}'s core annotation processor.
 *
 * @since 0.25.0
 */
// This delivered seven types until 0.30.0 removed six of the constants that reached them, and
// it now delivers FSM alone. Whether an SPI is the right shape for one type is a fair question
// and deliberately not answered here: it is a decision about the extension point, not a
// consequence of a removal.
public final class ExtBehaviorAnnotationExtension implements BehaviorAnnotationExtension {

    private static final Logger log = LoggerFactory.getLogger(ExtBehaviorAnnotationExtension.class);

    private static final Set<BehaviorType> SUPPORTED = EnumSet.of(BehaviorType.FSM);

    @Override
    public boolean supports(BehaviorType type) {
        return SUPPORTED.contains(type);
    }

    @Override
    public dev.agenor.core.Behavior createBehavior(Agent agent, Method method, Behavior annotation) {
        return switch (annotation.type()) {
            case FSM -> createFSMBehavior(agent, method, annotation);
            default -> throw new IllegalStateException("Unsupported behavior type: " + annotation.type());
        };
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
