# Getting Started with Agenor

Get Agenor running in 5 minutes — no code to write yet.

## Prerequisites

- Java 21+
- Maven 3.9+

## 1. Clone and Build

```bash
git clone https://github.com/mauro-mura/agenor.git
cd agenor
mvn clean install -DskipTests
```

## 2. Run Your First Example

```bash
mvn exec:java -pl agenor-examples \
  -Dexec.mainClass="dev.agenor.examples.PingPongExample"
```

You should see two agents exchanging messages in the console. That's the runtime up and running.

## 3. Write One Yourself

Running someone else's example only shows you that it works. The next fifteen minutes are the
ones that matter: **[Your First Agent](first-agent.md)** — two agents, one asking and one
answering, six concepts, every line written by you.

```bash
mvn exec:java -pl agenor-examples   -Dexec.mainClass="dev.agenor.examples.QuickStartExample"
```

## 4. Try More Examples

```bash
# Cyclic behavior + topic pub/sub
mvn exec:java -pl agenor-examples \
  -Dexec.mainClass="dev.agenor.examples.WeatherStationExample"

# FSM + parallel validators (production-style orchestration)
mvn exec:java -pl agenor-examples \
  -Dexec.mainClass="dev.agenor.examples.ecommerce.ECommerceApplication"

# LLM multi-agent (requires OPENAI_API_KEY env var)
mvn exec:java -pl agenor-examples \
  -Dexec.mainClass="dev.agenor.examples.llm.LLMDirectMessagingExample"
```

The full learning path (Level 0 → Level 5) is in `agenor-examples/README.md`.

## 5. Add Agenor to Your Project

Agenor is on [Maven Central](https://central.sonatype.com/artifact/dev.agenor/agenor-bom): this
step needs no clone and no local build. Agenor ships no logging backend, so add one as well —
see [Installation](https://github.com/mauro-mura/agenor#installation) in the README.

```xml
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>dev.agenor</groupId>
            <artifactId>agenor-bom</artifactId>
            <version>0.39.0</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>

<dependencies>
    <dependency>
        <groupId>dev.agenor</groupId>
        <artifactId>agenor-runtime</artifactId>
    </dependency>
</dependencies>
```

`agenor-runtime` alone gives you `ONE_SHOT` and `CYCLIC`. `FSM`, the composites and
Human-in-the-Loop behaviors require an extra `agenor-runtime-ext` dependency (ADR-027) — see the
[Behaviors Overview](behaviors/README.md).

### On the module path

Skip this if your application has no `module-info.java`: on the classpath, which is where Maven
`exec`, the Spring Boot starter and every example run, none of it applies.

Agenor's JARs are automatic modules (`dev.agenor.core`, `dev.agenor.runtime`, …). An automatic
module declares no dependencies, so your module has to bring two of Agenor's into the module
graph itself, and has to open the package that holds your agents to the modules that call them
reflectively:

```java
module com.example.agents {
    requires dev.agenor.core;
    requires dev.agenor.runtime;
    requires org.slf4j;
    requires com.fasterxml.jackson.databind;

    opens com.example.agents to dev.agenor.runtime, dev.agenor.runtime.scanning,
                                com.fasterxml.jackson.databind;
}
```

Leave the `opens` line out and `start()` still succeeds, but the agent does nothing: every
`@Behavior` and `@AgenorMessageHandler` fails with `InaccessibleObjectException`, and
`scanPackage` cannot instantiate the agents it finds. The only trace is an `ERROR` line in the log.
Jackson is in the list because it builds your own content types, such as a record read with
`message.getContent(MyRecord.class)`, from the `Map` that a payload becomes after crossing Redis.
Leave out the two `requires` lines and the runtime fails with `NoClassDefFoundError` when it first
logs or builds a `Message`.

## Next Steps

| I want to… | Go to |
|------------|-------|
| Write my first agent | [Your First Agent](first-agent.md) |
| Go deeper on agent structure | [Agent Development Guide](agent-development.md) |
| Understand the module structure | [Architecture Guide](architecture.md) |
| Browse all behavior types | [Behaviors Overview](behaviors/README.md) |
| Integrate an LLM provider | [LLM Integration Guide](llm-integration.md) |
| Configure Agenor via YAML | [Configuration Guide](configuration.md) |
