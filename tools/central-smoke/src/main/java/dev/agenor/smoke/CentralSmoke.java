package dev.agenor.smoke;

import dev.agenor.core.Message;
import dev.agenor.runtime.AgenorRuntime;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Runs the README's "Running" snippet against whatever version of Agenor was
 * resolved from Maven Central, and fails the build if nothing is delivered.
 *
 * <p>A smoke test that only starts the runtime would pass on a broken release: the
 * cyclic behaviour would never fire and the process would exit 0. So this subscribes
 * to the same topic the agent publishes on and waits for a message, which exercises
 * the annotation processor, the scheduler and the dispatcher in one go.
 */
public final class CentralSmoke {

    /** The behaviour's interval is 5s, so one message is due well inside this. */
    private static final int TIMEOUT_SECONDS = 30;

    private CentralSmoke() {
    }

    public static void main(String[] args) throws Exception {
        var runtime = AgenorRuntime.builder().build();
        var received = new CountDownLatch(1);

        var subscription = runtime.getMessageDispatcher().subscribeTopic("greetings", message -> {
            System.out.println("[smoke] received on 'greetings': " + message.content());
            received.countDown();
            return CompletableFuture.completedFuture(null);
        });

        runtime.registerAgent(new HelloAgent());
        runtime.start().join();

        var delivered = received.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        subscription.unsubscribe();
        runtime.stop().join();

        if (!delivered) {
            System.err.println("[smoke] FAILED: no message on 'greetings' within "
                    + TIMEOUT_SECONDS + "s");
            System.exit(1);
        }
        System.out.println("[smoke] OK: " + Message.class.getPackage().getName()
                + " resolved from Central and the agent published and received");
    }
}
