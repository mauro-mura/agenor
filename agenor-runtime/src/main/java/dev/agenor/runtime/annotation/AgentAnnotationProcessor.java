package dev.agenor.runtime.annotation;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.time.Duration;
import java.util.Optional;

import dev.agenor.core.annotations.AgenorMessageHandler;
import dev.agenor.core.annotations.Behavior;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.agenor.core.Agent;
import dev.agenor.core.Message;
import dev.agenor.core.MessageHandler;
import dev.agenor.core.messaging.TopicSubscriber;
import dev.agenor.core.spi.BehaviorAnnotationExtension;
import dev.agenor.runtime.agent.BaseAgent;
import dev.agenor.runtime.behavior.BaseBehavior;
import dev.agenor.runtime.behavior.CyclicBehavior;
import dev.agenor.runtime.behavior.EventDrivenBehavior;
import dev.agenor.runtime.behavior.OneShotBehavior;
import dev.agenor.runtime.behavior.WakerBehavior;

/**
 * Processor for handling {@code @Behavior} and {@code @AgenorMessageHandler}
 * annotations on agent classes.
 *
 * <p>Handles {@code @AgenorMessageHandler} and the {@code @Behavior} types whose
 * implementation classes live in {@code agenor-runtime} (ONE_SHOT, CYCLIC, WAKER,
 * EVENT_DRIVEN, CUSTOM) directly — no optional-module dependency. Every other
 * {@code @Behavior} type is delegated to the {@link BehaviorAnnotationExtension} SPI,
 * implemented by {@code agenor-runtime-ext}; when that extension is absent, using one
 * of those types fails loudly with an {@link IllegalStateException} rather than being
 * silently skipped.
 *
 * @since 0.25.0
 */
public class AgentAnnotationProcessor {

    private static final Logger log = LoggerFactory.getLogger(AgentAnnotationProcessor.class);

    private final TopicSubscriber topicSubscriber;
    private final Optional<BehaviorAnnotationExtension> extension;

    public AgentAnnotationProcessor(TopicSubscriber topicSubscriber,
                                     Optional<BehaviorAnnotationExtension> extension) {
        this.topicSubscriber = topicSubscriber;
        this.extension = extension;
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
                } catch (IllegalStateException e) {
                    // missing agenor-runtime-ext for an ext-only @Behavior type — fail
                    // loudly instead of the log-and-skip below
                    throw e;
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
            case CUSTOM -> createCustomBehavior(agent, method, annotation);
            default -> delegateToExtension(agent, method, annotation);
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

    /**
     * Delegates {@code @Behavior} types not handled directly (CONDITIONAL, THROTTLED,
     * BATCH, RETRY, SEQUENTIAL, PARALLEL, FSM) to the {@link BehaviorAnnotationExtension}
     * SPI, implemented by {@code agenor-runtime-ext}.
     */
    private dev.agenor.core.Behavior delegateToExtension(Agent agent, Method method, Behavior annotation) {
        BehaviorAnnotationExtension ext = extension.orElseThrow(() -> new IllegalStateException(
                "@Behavior(type=" + annotation.type() + ") requires agenor-runtime-ext on the classpath"));
        if (!ext.supports(annotation.type())) {
            throw new UnsupportedOperationException("Behavior type not supported: " + annotation.type());
        }
        return ext.createBehavior(agent, method, annotation);
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

        // Also wire the handler to direct (point-to-point) messages addressed to this
        // agent whose topic matches, so @AgenorMessageHandler works with sendTo()/receiverId
        // addressing, not just topic pub/sub (see BaseAgent#registerDirectTopicHandler).
        if (agent instanceof BaseAgent baseAgent) {
            baseAgent.registerDirectTopicHandler(topic, handler);
        }

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
