package dev.agenor.runtime.dialogue;

import dev.agenor.core.Agent;
import dev.agenor.core.AgentDescriptor;
import dev.agenor.core.AgentStatus;
import dev.agenor.core.Behavior;
import dev.agenor.core.dialogue.DialogueHandler;
import dev.agenor.core.dialogue.DialogueMessage;
import dev.agenor.core.dialogue.Performative;
import dev.agenor.runtime.directory.InMemoryAgentDirectory;
import dev.agenor.runtime.messaging.InMemoryMessageDispatcher;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test: full dialogue round-trip using real InMemoryMessageDispatcher
 * and InMemoryAgentDirectory (plain Agent implementations, not BaseAgent).
 */
class DialogueRoundTripTest {

    @Test
    void queryRoundTripWithPlainAgents() throws Exception {
        InMemoryAgentDirectory directory = new InMemoryAgentDirectory();
        InMemoryMessageDispatcher dispatcher = new InMemoryMessageDispatcher(directory);

        KbAgent kb = new KbAgent(dispatcher, directory);
        ClientAgent client = new ClientAgent(dispatcher, directory);

        kb.start().join();
        client.start().join();

        DialogueMessage response = client.ask("hello").get(3, TimeUnit.SECONDS);

        assertThat(response.performative()).isEqualTo(Performative.INFORM);
        assertThat(response.content()).isEqualTo("world");
    }

    // -------------------------------------------------------------------------

    static class KbAgent implements Agent {
        private final InMemoryMessageDispatcher dispatcher;
        private final InMemoryAgentDirectory directory;
        private final DialogueCapability dialogue = new DialogueCapability(this);

        KbAgent(InMemoryMessageDispatcher dispatcher, InMemoryAgentDirectory directory) {
            this.dispatcher = dispatcher;
            this.directory = directory;
        }

        @Override public String getAgentId() { return "kb"; }
        @Override public String getAgentName() { return "KB"; }
        @Override public boolean isRunning() { return true; }
        @Override public void addBehavior(Behavior b) {}
        @Override public void removeBehavior(String id) {}
        @Override public dev.agenor.core.messaging.MessageDispatcher getMessageDispatcher() { return dispatcher; }

        @Override
        public CompletableFuture<Void> start() {
            return CompletableFuture.runAsync(() -> {
                directory.register(AgentDescriptor.builder("kb")
                        .agentName("KB").agentType("KbAgent")
                        .status(AgentStatus.RUNNING).build()).join();
                dialogue.initialize(dispatcher);
            });
        }

        @Override
        public CompletableFuture<Void> stop() {
            return CompletableFuture.runAsync(() -> {
                dialogue.shutdown();
                directory.unregister("kb").join();
            });
        }

        @DialogueHandler(performatives = Performative.QUERY)
        public void handleQuery(DialogueMessage msg) {
            dialogue.inform(msg, "world");
        }
    }

    static class ClientAgent implements Agent {
        private final InMemoryMessageDispatcher dispatcher;
        private final InMemoryAgentDirectory directory;
        private final DialogueCapability dialogue = new DialogueCapability(this);

        ClientAgent(InMemoryMessageDispatcher dispatcher, InMemoryAgentDirectory directory) {
            this.dispatcher = dispatcher;
            this.directory = directory;
        }

        @Override public String getAgentId() { return "client"; }
        @Override public String getAgentName() { return "Client"; }
        @Override public boolean isRunning() { return true; }
        @Override public void addBehavior(Behavior b) {}
        @Override public void removeBehavior(String id) {}
        @Override public dev.agenor.core.messaging.MessageDispatcher getMessageDispatcher() { return dispatcher; }

        @Override
        public CompletableFuture<Void> start() {
            return CompletableFuture.runAsync(() -> {
                directory.register(AgentDescriptor.builder("client")
                        .agentName("Client").agentType("ClientAgent")
                        .status(AgentStatus.RUNNING).build()).join();
                dialogue.initialize(dispatcher);
            });
        }

        @Override
        public CompletableFuture<Void> stop() {
            return CompletableFuture.runAsync(() -> {
                dialogue.shutdown();
                directory.unregister("client").join();
            });
        }

        CompletableFuture<DialogueMessage> ask(String query) {
            return dialogue.query("kb", query, Duration.ofSeconds(3));
        }
    }
}
