package dev.agenor.core.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a field or method for automatic inclusion in agent state persistence.
 *
 * <p>The runtime persistence layer reads all members annotated with {@code @Persist}
 * when saving agent state, and restores their values when the agent is reloaded.
 * By default, the persistence key matches the Java member name; set {@link #value()}
 * to override it (useful when the serialized schema must remain stable across renames).
 *
 * <h2>Field usage</h2>
 * <pre>{@code
 * @Persist
 * private int processedCount = 0;
 *
 * @Persist("customer_id")   // explicit key in persisted state
 * private String customerId;
 *
 * @Persist(required = true) // restoration fails if key is absent
 * private String sessionToken;
 *
 * @Persist(encrypted = true) // value is encrypted at rest
 * private String apiKey;
 * }</pre>
 *
 * <h2>Method usage</h2>
 * <p>When placed on a getter/setter pair, the framework will call the getter on save
 * and the setter on restore.
 *
 * <pre>{@code
 * @Persist("order_state")
 * public OrderState getOrderState() { return orderState; }
 * }</pre>
 *
 * <p>The persistence strategy and snapshot schedule are configured at class level via
 * {@link PersistenceConfig}.
 *
 * @since 0.2.0
 * @see PersistenceConfig
 */
@Target({ElementType.FIELD, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface Persist {

    /**
     * Key used to identify this field in the persisted state document.
     * Defaults to the Java field or method name when left empty.
     */
    String value() default "";

    /**
     * When {@code true}, state restoration fails with an exception if this key
     * is missing from the persisted document. Use for fields that are essential
     * to resume correct operation (e.g., session tokens, transaction IDs).
     */
    boolean required() default false;

    /**
     * When {@code true}, the value is encrypted before being written to the
     * persistence store and decrypted transparently on restore.
     * Suitable for secrets such as API keys or access tokens.
     */
    boolean encrypted() default false;
}
