package dev.agenor.runtime.dialogue;

import dev.agenor.core.dialogue.DialogueHandler;
import dev.agenor.core.dialogue.DialogueMessage;
import dev.agenor.core.dialogue.Performative;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class DialogueHandlerRegistryTest {

    private DialogueHandlerRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new DialogueHandlerRegistry();
    }

    @Test
    void shouldScanAndRegisterHandlers() {
        var handler = new TestHandler();
        registry.scan(handler);

        assertThat(registry.size()).isEqualTo(3);
    }

    @Test
    void shouldDispatchToMatchingHandler() {
        var handler = new TestHandler();
        registry.scan(handler);

        var message = DialogueMessage.builder()
            .senderId("sender")
            .performative(Performative.REQUEST)
            .content("test")
            .build();

        boolean dispatched = registry.dispatch(message);

        assertThat(dispatched).isTrue();
        assertThat(handler.requestReceived.get()).isNotNull();
        assertThat(handler.requestReceived.get().content()).isEqualTo("test");
    }

    @Test
    void shouldNotDispatchToNonMatchingHandler() {
        var handler = new TestHandler();
        registry.scan(handler);

        var message = DialogueMessage.builder()
            .senderId("sender")
            .performative(Performative.CANCEL)
            .build();

        boolean dispatched = registry.dispatch(message);

        // CANCEL is not handled by TestHandler
        assertThat(dispatched).isFalse();
    }

    @Test
    void shouldFilterByProtocol() {
        var handler = new TestHandler();
        registry.scan(handler);

        // CFP without protocol - should not match protocolHandler
        var cfpNoProtocol = DialogueMessage.builder()
            .senderId("sender")
            .performative(Performative.CFP)
            .content("task")
            .build();

        registry.dispatch(cfpNoProtocol);
        assertThat(handler.cfpReceived.get()).isNull();

        // CFP with contract-net protocol - should match
        var cfpWithProtocol = DialogueMessage.builder()
            .senderId("sender")
            .performative(Performative.CFP)
            .content("task")
            .protocol("contract-net")
            .build();

        registry.dispatch(cfpWithProtocol);
        assertThat(handler.cfpReceived.get()).isNotNull();
    }

    @Test
    void shouldRespectPriority() {
        var handler = new PriorityHandler();
        registry.scan(handler);

        var message = DialogueMessage.builder()
            .senderId("sender")
            .performative(Performative.INFORM)
            .build();

        registry.dispatch(message);

        // High priority handler should be called first
        assertThat(handler.callOrder.toString()).isEqualTo("high,low");
    }

    @Test
    void shouldHandleMultiplePerformatives() {
        var handler = new TestHandler();
        registry.scan(handler);

        var request = DialogueMessage.builder()
            .senderId("s").performative(Performative.REQUEST).build();
        var query = DialogueMessage.builder()
            .senderId("s").performative(Performative.QUERY).build();

        registry.dispatch(request);
        registry.dispatch(query);

        assertThat(handler.requestReceived.get()).isNotNull();
        assertThat(handler.queryReceived.get()).isNotNull();
    }

    @Test
    void shouldRegisterHandlersInheritedFromAnAbstractBaseClass() {
        // Given a concrete agent whose @DialogueHandler lives on its abstract base
        var handler = new ConcreteWorker();
        registry.scan(handler);

        assertThat(registry.size()).isEqualTo(2); // one inherited, one declared

        // When a message the base class handles arrives
        boolean dispatched = registry.dispatch(DialogueMessage.builder()
            .senderId("sender")
            .performative(Performative.REQUEST)
            .content("task")
            .build());

        // Then the inherited handler fires exactly once
        assertThat(dispatched).isTrue();
        assertThat(handler.baseCalls.get()).isEqualTo(1);
    }

    @Test
    void shouldRegisterAnOverriddenHandlerOnlyOnce() {
        var handler = new OverridingWorker();
        registry.scan(handler);

        registry.dispatch(DialogueMessage.builder()
            .senderId("sender")
            .performative(Performative.REQUEST)
            .content("task")
            .build());

        assertThat(registry.size()).isEqualTo(1);
        assertThat(handler.overrideCalls.get()).isEqualTo(1);
        assertThat(handler.baseCalls.get()).isZero();
    }

    @Test
    void shouldRegisterAHandlerDeclaredAsAnInterfaceDefaultMethod() {
        // Given a handler that comes from an interface rather than a base class
        var handler = new InterfaceWorker();
        registry.scan(handler);

        // When
        registry.dispatch(DialogueMessage.builder()
            .senderId("sender")
            .performative(Performative.REQUEST)
            .content("task")
            .build());

        // Then it fired: the scan follows interfaces, not only the superclass chain
        assertThat(registry.size()).isEqualTo(1);
        assertThat(handler.defaultCalls.get()).isEqualTo(1);
    }

    @Test
    void shouldNotDoubleRegisterWhenScannedTwice() {
        var handler = new TestHandler();
        registry.scan(handler);
        registry.scan(handler);

        assertThat(registry.size()).isEqualTo(3);
    }

    // Base class carrying a shared handler - the natural OOP pattern
    abstract static class AbstractWorker {
        final AtomicInteger baseCalls = new AtomicInteger();

        @DialogueHandler(performatives = Performative.REQUEST)
        public void handleRequest(DialogueMessage msg) {
            baseCalls.incrementAndGet();
        }
    }

    static class ConcreteWorker extends AbstractWorker {
        final AtomicInteger ownCalls = new AtomicInteger();

        @DialogueHandler(performatives = Performative.QUERY)
        public void handleQuery(DialogueMessage msg) {
            ownCalls.incrementAndGet();
        }
    }

    static class OverridingWorker extends AbstractWorker {
        final AtomicInteger overrideCalls = new AtomicInteger();

        @Override
        @DialogueHandler(performatives = Performative.REQUEST)
        public void handleRequest(DialogueMessage msg) {
            overrideCalls.incrementAndGet();
        }
    }

    // Shared handler on an interface - the other natural way to share one
    interface WorkerCapability {

        void countDefaultCall();

        @DialogueHandler(performatives = Performative.REQUEST)
        default void handleRequest(DialogueMessage msg) {
            countDefaultCall();
        }
    }

    static class InterfaceWorker implements WorkerCapability {
        final AtomicInteger defaultCalls = new AtomicInteger();

        @Override
        public void countDefaultCall() {
            defaultCalls.incrementAndGet();
        }
    }

    // Test handler class
    static class TestHandler {
        final AtomicReference<DialogueMessage> requestReceived = new AtomicReference<>();
        final AtomicReference<DialogueMessage> queryReceived = new AtomicReference<>();
        final AtomicReference<DialogueMessage> cfpReceived = new AtomicReference<>();

        @DialogueHandler(performatives = Performative.REQUEST)
        public void handleRequest(DialogueMessage msg) {
            requestReceived.set(msg);
        }

        @DialogueHandler(performatives = Performative.QUERY)
        public void handleQuery(DialogueMessage msg) {
            queryReceived.set(msg);
        }

        @DialogueHandler(performatives = Performative.CFP, protocol = "contract-net")
        public void handleCfp(DialogueMessage msg) {
            cfpReceived.set(msg);
        }
    }

    // Priority test handler
    static class PriorityHandler {
        final StringBuilder callOrder = new StringBuilder();

        @DialogueHandler(performatives = Performative.INFORM, priority = 10)
        public void highPriority(DialogueMessage msg) {
            if (callOrder.length() > 0) callOrder.append(",");
            callOrder.append("high");
        }

        @DialogueHandler(performatives = Performative.INFORM, priority = 1)
        public void lowPriority(DialogueMessage msg) {
            if (callOrder.length() > 0) callOrder.append(",");
            callOrder.append("low");
        }
    }
}
