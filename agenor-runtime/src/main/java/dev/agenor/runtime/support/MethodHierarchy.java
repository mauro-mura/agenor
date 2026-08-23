package dev.agenor.runtime.support;

import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Collects the methods of a class the way annotation scanning needs to see them.
 *
 * <p>{@link Class#getDeclaredMethods()} alone stops at the class itself, so an annotated
 * method declared on an abstract base agent — or on an interface the agent implements — is
 * invisible to a scan of the concrete class. Silently: a missing registration produces no
 * error, only an agent that never receives anything. {@link Class#getMethods()} is not the
 * answer either: it skips non-public methods and offers no control over overrides.
 *
 * @since 0.27.0
 */
public final class MethodHierarchy {

    private MethodHierarchy() {
    }

    /**
     * Returns every method visible on {@code type}, each at its most-derived declaration.
     *
     * <p>Classes come first, walking the superclass chain up to but excluding {@code Object},
     * then the interfaces that {@code type} and its superclasses implement, most-derived
     * first. A method is considered once, at the first declaration found in that order:
     *
     * <ul>
     *   <li>a class declaration wins over an interface default method it overrides;</li>
     *   <li>a subclass override wins over the method it overrides;</li>
     *   <li>nothing is returned twice, so a handler is registered once, and invocation stays
     *       virtual — even a base-class or interface {@link Method} dispatches to the
     *       most-derived implementation.</li>
     * </ul>
     *
     * <p>One rule follows from that ordering and is worth stating plainly, because Java does
     * not inherit method annotations: <strong>the most-derived declaration decides</strong>.
     * Overriding an annotated method without re-annotating it removes the annotation from the
     * scan's view, whether the annotated declaration was on a base class or on an interface.
     *
     * <p>From interfaces only {@code default} methods are taken. An abstract interface method
     * has no body to invoke and is always implemented further down, where the scan sees it
     * first anyway. Static methods on interfaces are not inherited and are skipped for the
     * same reason.
     *
     * <p>Synthetic and bridge methods are skipped throughout — the compiler generates those
     * for generics and covariant returns, and registering one would run a handler twice.
     *
     * @param type the class to walk; must not be {@code null}
     * @return the methods, most-derived declarations first, never {@code null}
     */
    public static List<Method> mostDerivedMethods(Class<?> type) {
        var result = new ArrayList<Method>();
        var seenSignatures = new HashSet<String>();

        for (Class<?> current = type; current != null && current != Object.class;
                current = current.getSuperclass()) {
            collect(current.getDeclaredMethods(), false, result, seenSignatures);
        }
        for (Class<?> iface : interfacesOf(type)) {
            collect(iface.getDeclaredMethods(), true, result, seenSignatures);
        }
        return result;
    }

    /**
     * Identity of an overridable method: name plus parameter types, ignoring the declaring
     * class, so that an override and the method it overrides collapse to one entry.
     *
     * @param method the method to identify; must not be {@code null}
     * @return a signature string, never {@code null}
     */
    public static String signatureOf(Method method) {
        return method.getName() + Arrays.toString(method.getParameterTypes());
    }

    private static void collect(Method[] declared, boolean fromInterface,
                                List<Method> result, Set<String> seenSignatures) {
        for (Method method : declared) {
            if (method.isSynthetic() || method.isBridge()) {
                continue;
            }
            if (fromInterface && !method.isDefault()) {
                continue;
            }
            if (seenSignatures.add(signatureOf(method))) {
                result.add(method);
            }
        }
    }

    /**
     * Every interface reachable from {@code type}, most-derived first.
     *
     * <p>Breadth-first from the interfaces of the class itself, then of its superclasses, then
     * of the interfaces already found. That order puts a sub-interface ahead of the interface
     * it extends, so a default method overridden in a sub-interface is taken from the more
     * specific one.
     */
    private static List<Class<?>> interfacesOf(Class<?> type) {
        var ordered = new LinkedHashSet<Class<?>>();
        var queue = new ArrayDeque<Class<?>>();

        for (Class<?> current = type; current != null && current != Object.class;
                current = current.getSuperclass()) {
            Collections.addAll(queue, current.getInterfaces());
        }
        while (!queue.isEmpty()) {
            Class<?> iface = queue.poll();
            if (ordered.add(iface)) {
                Collections.addAll(queue, iface.getInterfaces());
            }
        }
        return List.copyOf(ordered);
    }
}
