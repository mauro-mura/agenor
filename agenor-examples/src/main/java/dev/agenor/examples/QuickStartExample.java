package dev.agenor.examples;

import dev.agenor.core.dialogue.DialogueHandler;
import dev.agenor.core.dialogue.DialogueMessage;
import dev.agenor.core.dialogue.Performative;
import dev.agenor.runtime.AgenorRuntime;
import dev.agenor.runtime.agent.BaseAgent;
import dev.agenor.runtime.dialogue.DialogueCapability;

import java.util.concurrent.CompletableFuture;

/**
 * One agent asks, another answers. The shortest path to a working agent system.
 *
 * <p>Six concepts, and the count is the point — every one of them is needed to make this run,
 * and nothing else is:
 *
 * <ol>
 *   <li>{@link AgenorRuntime} — holds the agents and starts them</li>
 *   <li>{@link BaseAgent} — what an agent extends</li>
 *   <li>{@link DialogueCapability} — the ability to ask and answer</li>
 *   <li>{@link DialogueHandler} — marks the method that receives</li>
 *   <li>{@link Performative} — what kind of message it is: a QUERY, an INFORM</li>
 *   <li>{@link DialogueMessage} — what a handler is handed</li>
 * </ol>
 *
 * <p>Behaviours, topics, the directory and the message builder do not appear here. They are
 * real, and they are the next thing to learn — but none of them is needed to make two agents
 * talk, so none of them is in the way.
 *
 * <p>Run with:
 * <pre>
 * mvn exec:java -pl agenor-examples \
 *     -Dexec.mainClass="dev.agenor.examples.QuickStartExample"
 * </pre>
 */
public class QuickStartExample {

    public static void main(String[] args) {
        // The runtime holds the agents. Its defaults are in-memory, so nothing else to start.
        AgenorRuntime runtime = AgenorRuntime.builder().build();

        LibrarianAgent librarian = new LibrarianAgent();
        ReaderAgent reader = new ReaderAgent();

        runtime.registerAgent(librarian);
        runtime.registerAgent(reader);
        runtime.start().join();

        // Ask, and wait for the answer. The reply arrives as a message, not a return value,
        // because the agent answering it might be in another process.
        DialogueMessage answer = reader.askAbout("Kafka").join();
        System.out.println("Reader asked about Kafka");
        System.out.println("Librarian answered: " + answer.content());

        runtime.stop().join();
    }

    /** Answers questions about the catalogue. */
    static class LibrarianAgent extends BaseAgent {

        // Declaring the field is the whole wiring: the capability hooks itself onto the
        // agent's start and stop.
        private final DialogueCapability dialogue = new DialogueCapability(this);

        LibrarianAgent() {
            super("librarian", "Librarian");
        }

        @DialogueHandler(performatives = Performative.QUERY)
        public void onQuestion(DialogueMessage question) {
            String topic = String.valueOf(question.content());
            dialogue.inform(question, "we have 3 books about " + topic);
        }
    }

    /** Asks the librarian, and gets an answer back. */
    static class ReaderAgent extends BaseAgent {

        private final DialogueCapability dialogue = new DialogueCapability(this);

        ReaderAgent() {
            super("reader", "Reader");
        }

        CompletableFuture<DialogueMessage> askAbout(String topic) {
            return dialogue.query("librarian", topic);
        }
    }
}
