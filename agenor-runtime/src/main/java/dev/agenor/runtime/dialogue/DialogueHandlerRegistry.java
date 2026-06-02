package dev.agenor.runtime.dialogue;

import dev.agenor.core.dialogue.DialogueHandler;
import dev.agenor.core.dialogue.DialogueMessage;
import dev.agenor.core.dialogue.Performative;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Registry for dialogue message handlers discovered from @DialogueHandler annotations.
 *
 * @since 0.5.0
 */
public class DialogueHandlerRegistry {

    private static final Logger log = LoggerFactory.getLogger(DialogueHandlerRegistry.class);

    private final List<HandlerEntry> handlers = new ArrayList<>();
    private final Map<String, List<HandlerEntry>> byProtocol = new ConcurrentHashMap<>();
    private final Map<Performative, List<HandlerEntry>> byPerformative = new ConcurrentHashMap<>();

    /**
     * Scans an object for @DialogueHandler annotated methods and registers them.
     *
     * @param target the object to scan
     */
    public void scan(Object target) {
        Class<?> clazz = target.getClass();

        for (Method method : clazz.getDeclaredMethods()) {
            DialogueHandler annotation = method.getAnnotation(DialogueHandler.class);
            if (annotation != null) {
                registerHandler(target, method, annotation);
            }
        }

        // Sort by priority (descending)
        handlers.sort(Comparator.comparingInt(HandlerEntry::priority).reversed());
        byProtocol.values().forEach(list ->
            list.sort(Comparator.comparingInt(HandlerEntry::priority).reversed()));
        byPerformative.values().forEach(list ->
            list.sort(Comparator.comparingInt(HandlerEntry::priority).reversed()));

        log.debug("Registered {} dialogue handlers for {}", handlers.size(), clazz.getSimpleName());
    }

    /**
     * Registers a handler method.
     */
    private void registerHandler(Object target, Method method, DialogueHandler annotation) {
        // Validate method signature
        Class<?>[] params = method.getParameterTypes();
        if (params.length != 1 || !DialogueMessage.class.isAssignableFrom(params[0])) {
            log.warn("Invalid @DialogueHandler method signature: {} - must accept DialogueMessage",
                method.getName());
            return;
        }

        method.setAccessible(true);

        Set<Performative> performatives = annotation.performatives().length > 0
            ? Set.of(annotation.performatives())
            : Set.of(Performative.values()); // all if empty

        String protocol = annotation.protocol().isEmpty() ? null : annotation.protocol();

        Consumer<DialogueMessage> invoker = msg -> {
            try {
                method.invoke(target, msg);
            } catch (Exception e) {
                log.error("Error invoking dialogue handler {}: {}", method.getName(), e.getMessage(), e);
            }
        };

        var entry = new HandlerEntry(
            method.getName(),
            performatives,
            protocol,
            annotation.priority(),
            invoker
        );

        handlers.add(entry);

        // Index by protocol
        if (protocol != null) {
            byProtocol.computeIfAbsent(protocol, k -> new ArrayList<>()).add(entry);
        }

        // Index by performative
        for (Performative perf : performatives) {
            byPerformative.computeIfAbsent(perf, k -> new ArrayList<>()).add(entry);
        }

        log.debug("Registered dialogue handler: {} for performatives={}, protocol={}",
            method.getName(), performatives, protocol);
    }

    /**
     * Dispatches a message to matching handlers.
     *
     * @param message the message to dispatch
     * @return true if at least one handler was invoked
     */
    public boolean dispatch(DialogueMessage message) {
        List<HandlerEntry> matching = findMatchingHandlers(message);

        if (matching.isEmpty()) {
            return false;
        }

        for (HandlerEntry handler : matching) {
            handler.invoker.accept(message);
        }

        return true;
    }

    /**
     * Finds handlers that match the given message.
     */
    private List<HandlerEntry> findMatchingHandlers(DialogueMessage message) {
        List<HandlerEntry> result = new ArrayList<>();

        for (HandlerEntry handler : handlers) {
            if (matches(handler, message)) {
                result.add(handler);
            }
        }

        return result;
    }

    /**
     * Checks if a handler matches a message.
     */
    private boolean matches(HandlerEntry handler, DialogueMessage message) {
        // Check performative
        if (!handler.performatives.contains(message.performative())) {
            return false;
        }

        // Check protocol (null handler.protocol means "any protocol")
        if (handler.protocol != null) {
            String msgProtocol = message.protocol();
            if (msgProtocol == null || !handler.protocol.equals(msgProtocol)) {
                return false;
            }
        }

        return true;
    }

    /**
     * @return number of registered handlers
     */
    public int size() {
        return handlers.size();
    }

    /**
     * Internal handler entry.
     */
    private record HandlerEntry(
        String name,
        Set<Performative> performatives,
        String protocol,
        int priority,
        Consumer<DialogueMessage> invoker
    ) {}
}
