package dev.agenor.runtime.support;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;

/**
 * Collects the methods of a class the way annotation scanning needs to see them.
 *
 * <p>{@link Class#getDeclaredMethods()} alone stops at the class itself, so an annotated
 * method declared on an abstract base agent is invisible to a scan of its concrete subclass —
 * silently, since a missing registration produces no error, only an agent that never receives
 * anything. {@link Class#getMethods()} is not the answer either: it skips non-public methods
 * and offers no control over overrides.
 *
 * @since 0.27.0
 */
public final class MethodHierarchy {

    private MethodHierarchy() {
    }

    /**
     * Returns every method visible on {@code type}, each at its most-derived declaration.
     *
     * <p>The superclass chain is walked up to but excluding {@code Object}. A method is
     * considered once: an override wins over the method it overrides and is never returned
     * twice, so a handler is registered once and the subclass's version is the one kept.
     * Invocation stays virtual, so even a base-class {@link Method} dispatches to the
     * override.
     *
     * <p>Synthetic and bridge methods are skipped — the compiler generates those for generics
     * and covariant returns, and registering one would run a handler twice.
     *
     * <p>Interface default methods are not included: a scan follows classes, matching what
     * the dialogue handler registry has always done.
     *
     * @param type the class to walk; must not be {@code null}
     * @return the methods, most-derived declarations first, never {@code null}
     */
    public static List<Method> mostDerivedMethods(Class<?> type) {
        var result = new ArrayList<Method>();
        var seenSignatures = new HashSet<String>();

        for (Class<?> current = type; current != null && current != Object.class;
                current = current.getSuperclass()) {
            for (Method method : current.getDeclaredMethods()) {
                if (method.isSynthetic() || method.isBridge()) {
                    continue;
                }
                if (seenSignatures.add(signatureOf(method))) {
                    result.add(method);
                }
            }
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
}
