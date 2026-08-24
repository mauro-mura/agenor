# Your First Agent

Two agents. One asks a question, the other answers it. Fifteen minutes, and you will have
written every line yourself.

You need Java 21 and Maven 3.9. If you have not built Agenor yet, do that first — see
[Getting Started](getting-started.md).

## Six concepts, and that is the whole list

| | What it is |
|---|---|
| `AgenorRuntime` | holds your agents and starts them |
| `BaseAgent` | what your agent extends |
| `DialogueCapability` | the ability to ask and to answer |
| `@DialogueHandler` | marks the method that receives |
| `Performative` | what kind of message it is — a `QUERY`, an `INFORM` |
| `DialogueMessage` | what your handler is handed |

Behaviours, topics, the agent directory and the message builder are all real and all useful.
None of them appears below, because none of them is needed to make two agents talk. You will
meet them when you need them.

## 1. The agent that answers

An agent is a class that extends `BaseAgent` and passes an id and a name upward. Give it a
`DialogueCapability` field — declaring the field is the entire wiring, because the capability
hooks itself onto the agent's start and stop.

```java
static class LibrarianAgent extends BaseAgent {

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
```

`@DialogueHandler` says: when a `QUERY` arrives, call this method. `dialogue.inform(question, …)`
sends the answer back to whoever asked — you never name the recipient, because the question
already knows where it came from.

## 2. The agent that asks

```java
static class ReaderAgent extends BaseAgent {

    private final DialogueCapability dialogue = new DialogueCapability(this);

    ReaderAgent() {
        super("reader", "Reader");
    }

    CompletableFuture<DialogueMessage> askAbout(String topic) {
        return dialogue.query("librarian", topic);
    }
}
```

`dialogue.query("librarian", topic)` addresses the other agent **by its id**. It returns a
future rather than a value, and that is the one thing worth slowing down for: the agent
answering might be in another process on another machine. Agenor does not hide that from you,
because pretending otherwise is what makes distributed systems surprising later.

## 3. Run them

```java
public static void main(String[] args) {
    AgenorRuntime runtime = AgenorRuntime.builder().build();

    LibrarianAgent librarian = new LibrarianAgent();
    ReaderAgent reader = new ReaderAgent();

    runtime.registerAgent(librarian);
    runtime.registerAgent(reader);
    runtime.start().join();

    DialogueMessage answer = reader.askAbout("Kafka").join();
    System.out.println("Librarian answered: " + answer.content());

    runtime.stop().join();
}
```

`AgenorRuntime.builder().build()` gives you in-memory messaging and an in-memory directory, so
there is nothing to install and nothing to configure. Swapping either for Redis or a database
later does not change any of the code above.

The complete file is
[`QuickStartExample.java`](https://github.com/mauro-mura/agenor/blob/main/agenor-examples/src/main/java/dev/agenor/examples/QuickStartExample.java).
Run it with:

```bash
mvn exec:java -pl agenor-examples \
    -Dexec.mainClass="dev.agenor.examples.QuickStartExample"
```

```
Reader asked about Kafka
Librarian answered: we have 3 books about Kafka
```

## What to reach for next

| I want my agent to… | Read |
|---|---|
| do something on a timer, or react to events | [Behaviors Overview](behaviors/README.md) |
| broadcast to many agents instead of asking one | [Messaging Guide](messaging.md) |
| find agents by capability rather than by id | [Agent Directory](directory.md) |
| negotiate, delegate, or run a full protocol | [Dialog Protocol](dialog-protocol.md) |
| run across several machines | [Distributed Quick Start](distributed-quick-start.md) |

Each of those adds concepts to the six above. That is the trade, and it is worth making
deliberately — start from the six, and add the seventh only when something you are building
actually asks for it.
