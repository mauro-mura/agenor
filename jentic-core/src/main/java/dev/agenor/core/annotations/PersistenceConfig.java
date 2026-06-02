package dev.agenor.core.annotations;

import dev.agenor.core.persistence.PersistenceStrategy;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Configures the persistence behavior for an agent class.
 *
 * <p>Place this annotation on any class that extends {@code BaseAgent} to control
 * <em>when</em> the runtime saves the fields marked with {@link Persist}
 * and how snapshots are managed.
 *
 * <h2>Example — periodic auto-save every 30 seconds</h2>
 * <pre>{@code
 * @Agent("order-processor")
 * @PersistenceConfig(
 *     strategy = PersistenceStrategy.PERIODIC,
 *     interval  = "30s"
 * )
 * public class OrderProcessorAgent extends BaseAgent {
 *
 *     @Persist(required = true)
 *     private String currentOrderId;
 *
 *     @Persist
 *     private int retryCount = 0;
 * }
 * }</pre>
 *
 * <h2>Example — save on stop and hourly snapshots, keep last 24</h2>
 * <pre>{@code
 * @PersistenceConfig(
 *     strategy         = PersistenceStrategy.ON_STOP,
 *     autoSnapshot     = true,
 *     snapshotInterval = "1h",
 *     maxSnapshots     = 24
 * )
 * public class CriticalAgent extends BaseAgent { ... }
 * }</pre>
 *
 * <p>When this annotation is absent the agent uses {@link PersistenceStrategy#MANUAL}
 * (no automatic persistence; the agent must call {@code persistState()} explicitly).
 *
 * @since 0.2.0
 * @see Persist
 * @see PersistenceStrategy
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface PersistenceConfig {

    /**
     * Determines when the runtime automatically saves agent state.
     * Defaults to {@link PersistenceStrategy#MANUAL} — the agent is responsible
     * for calling {@code persistState()} at the appropriate time.
     */
    PersistenceStrategy strategy() default PersistenceStrategy.MANUAL;

    /**
     * Save interval used by {@link PersistenceStrategy#PERIODIC} and
     * {@link PersistenceStrategy#DEBOUNCED}. Accepts human-readable durations
     * such as {@code "30s"}, {@code "5m"}, {@code "1h"}.
     * Ignored for other strategies.
     */
    String interval() default "60s";

    /**
     * When {@code true}, the runtime creates point-in-time snapshots on the
     * schedule defined by {@link #snapshotInterval()}. Snapshots allow rolling
     * back to a previous state independently of the normal save cycle.
     */
    boolean autoSnapshot() default false;

    /**
     * How often an automatic snapshot is taken. Accepts human-readable durations
     * such as {@code "1h"}, {@code "1d"}. Only relevant when {@link #autoSnapshot()}
     * is {@code true}.
     */
    String snapshotInterval() default "1h";

    /**
     * Maximum number of automatic snapshots to retain. Older snapshots are
     * deleted once this limit is exceeded (FIFO eviction).
     */
    int maxSnapshots() default 10;
}
