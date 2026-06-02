package dev.agenor.core.messaging;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link Subscription}.
 */
class SubscriptionTest {

    @Test
    @DisplayName("Subscription.of stores subscriptionId and invokes unsubscribe action")
    void ofCreatesSubscription() {
        var called = new AtomicBoolean(false);
        Subscription sub = Subscription.of("sub-123", () -> called.set(true));

        assertThat(sub.subscriptionId()).isEqualTo("sub-123");

        sub.unsubscribe();
        assertThat(called).isTrue();
    }

    @Test
    @DisplayName("unsubscribe can be called multiple times without error")
    void unsubscribeIdempotent() {
        var counter = new java.util.concurrent.atomic.AtomicInteger(0);
        Subscription sub = Subscription.of("s", counter::incrementAndGet);

        sub.unsubscribe();
        sub.unsubscribe();

        // The factory Runnable is called each time; no exception should be thrown
        assertThat(counter.get()).isEqualTo(2);
    }
}
