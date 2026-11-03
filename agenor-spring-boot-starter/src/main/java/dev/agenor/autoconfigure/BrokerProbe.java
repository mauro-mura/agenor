package dev.agenor.autoconfigure;

import java.util.function.BooleanSupplier;

/**
 * What {@link AgenorHealthIndicator} asks about the broker. It lets the indicator stay free of
 * Redis classes, which are absent from the classpath of an application that does not use them.
 *
 * @param transportType the name reported as {@code transport.type}
 * @param reachable     answers whether the broker can be reached; must be bounded and must not throw
 */
record BrokerProbe(String transportType, BooleanSupplier reachable) {
}
