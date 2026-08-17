# Distributed Quick Start

Running two Agenor nodes that can actually talk to each other takes two backends and one idea.
The backends are a **transport** (Redis Streams, ADR-021) and a **directory** (JDBC, ADR-023).
The idea is this:

> The `nodeId` you give the transport is what the directory advertises for every agent on that
> node, and it is what other nodes route to. If those two ever disagree, messages are written to
> a stream nobody reads — and nothing reports an error.

That sentence is the whole reason this page exists. Everything below is a consequence of it.

---

## 1. Start the backends

```bash
docker compose up -d          # compose.yml at the repository root
```

Or without compose:

```bash
docker run -d -p 5432:5432 -e POSTGRES_DB=agenor -e POSTGRES_USER=agenor \
           -e POSTGRES_PASSWORD=agenor postgres:16-alpine
docker run -d -p 6379:6379 valkey/valkey:8
```

The directory schema is created for you: `JdbcAgentDirectory.create(...)` runs its Flyway
migrations before returning.

---

## 2. Dependencies

Both adapters are opt-in (ADR-018), and Lettuce is `optional` in `agenor-adapters`, so declare it
yourself:

```xml
<dependency>
    <groupId>dev.agenor</groupId>
    <artifactId>agenor-adapters</artifactId>
</dependency>
<dependency>
    <groupId>dev.agenor</groupId>
    <artifactId>agenor-adapters-persistence</artifactId>
</dependency>
<dependency>
    <groupId>io.lettuce</groupId>
    <artifactId>lettuce-core</artifactId>
    <version>${lettuce.version}</version>
</dependency>
```

---

## 3. Wire one node

Each node needs a transport with **its own** `nodeId` and a directory pointing at the **shared**
database:

```java
var factory = RedisMessagingFactory.builder()
        .uri("redis://localhost:6379")
        .nodeId("node-1")              // unique per node — see the warning below
        .consumerGroupPrefix("agenor") // same on every node
        .build();

var directory = JdbcAgentDirectory.create(
        JdbcDirectoryConfig.of("jdbc:postgresql://localhost:5432/agenor", "agenor", "agenor"));

var runtime = AgenorRuntime.builder()
        // messageDispatcher(Supplier<AgentResolver>) — the resolver is read on every sendTo,
        // so cross-node routing works even if the directory is created later (e.g. a Spring bean)
        .messageDispatcher(factory.messageDispatcher(directory::resolver))
        .agentRegistry(directory.registry())
        .agentDiscovery(directory.discovery())
        .agentResolver(directory.resolver())
        // presence falls back to in-memory — see ADR-023
        .build();

runtime.registerAgent(new MyAgent());
runtime.start().join();
```

The second node is the same code with `nodeId("node-2")` and its own agents. Nothing else
differs: same Redis URI, same JDBC URL, same consumer-group prefix.

!!! warning "Give every node a distinct `nodeId`"
    Omit `nodeId(...)` and one is generated per JVM, which is correct but opaque. Set the **same**
    id on two nodes and they compete on one stream: each message goes to whichever consumer reads
    it first. Prefer something stable and meaningful — a hostname, a pod name, an ordinal.

    It is the `consumerGroupPrefix` that must match across nodes, not the `nodeId`. The prefix
    only namespaces the keys (`agenor:node:<nodeId>`); the node id is what separates them.

---

## 4. Send something across

Nothing in the agent code changes because it is distributed — that is the point of ADR-004. An
agent on node-1 addresses one on node-2 by id:

```java
dialogue.request("pricing-service", new QuoteRequest("SKU-42", 3, "EUR"), Duration.ofSeconds(15))
        .thenAccept(reply -> {
            Quote quote = reply.contentAs(Quote.class);   // not (Quote) reply.content()
            log.info("total {} {}", quote.total(), quote.currency());
        });
```

!!! danger "Use `contentAs(...)`, never a cast"
    On a single node the payload is the object you sent, and `(Quote) reply.content()` works. Once
    it crosses a serialising transport it arrives as a `LinkedHashMap` and the same cast throws
    `ClassCastException`. `contentAs(Class)` converts either shape (ADR-030), so code written with
    it keeps working when you add a second node — and code written with a cast breaks exactly when
    you deploy.

---

## 5. Check that the wiring is real

Discovery is asynchronous: `registerAgent` writes to the directory without blocking, so a node
may not see its peers the instant it starts. Wait for the endpoint rather than sleeping, and look
at what you get:

```java
var endpoint = runtime.getAgentDirectory().resolveEndpoint("pricing-service").join();
// expected: nodeId = "node-2", transportType = "redis"
```

A blank `nodeId`, or `transportType = "local"`, means the owning node registered without
advertising how to reach it. Messages sent to that agent will be written to a stream with no
consumer and will disappear **without any exception** — `sendTo` even returns a successfully
completed future. If you see this, the dispatcher is not implementing `LocalEndpointProvider`
(only networked transports do; the in-memory one deliberately does not).

Inspect the directory directly when in doubt:

```sql
SELECT agent_id, status, node_id, endpoint_transport_type FROM agenor_agents;
```

---

## Runnable example

`CrossRuntimeExample` does all of the above — two runtimes, distinct node ids, a typed
REQUEST/INFORM across Redis — in a single process so it stays one command:

```bash
mvn exec:java -pl agenor-examples \
    -Dexec.mainClass="dev.agenor.examples.distributed.CrossRuntimeExample"
```

It logs the resolved endpoint and the wire type of each payload, so you can see both mechanisms
rather than take them on trust. `CrossRuntimeDialogueIT` asserts the same path under
Testcontainers:

```bash
mvn verify -Dintegration.tests.enabled=true -pl agenor-examples
```

---

## Related

- [Redis Messaging Adapter](adapters/redis.md) — transport details, consumer groups, DLQ
- [JDBC Directory](adapters/jdbc-directory.md) — schema, capabilities, migrations
- [Agent Directory](directory.md) — the capability split behind `registry`/`resolver`/`discovery`
- [Dialog Protocol](dialog-protocol.md) — performatives, protocols, conversations
