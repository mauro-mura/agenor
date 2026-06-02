# ReflectionBehavior - Generate → Critique → Revise

## Overview

`ReflectionBehavior` wraps a generation action with an iterative self-critique loop.
After each output is produced, a `ReflectionStrategy` evaluates its quality and
decides whether a revision cycle is needed. The loop stops when the score meets the
configured threshold or `maxIterations` is reached.

**Since**: v0.12.0 | **Type**: `BehaviorType.ONE_SHOT` | **Package**: `dev.agenor.runtime.behavior`

---

## How It Works

```
┌─────────┐     ┌──────────┐     score >= threshold   ┌────────┐
│ action()│────>│ critique │─────────────────────────>│ DONE   │
└─────────┘     └────┬─────┘                          └────────┘
                     │ shouldRevise && iter < max
                     ▼
              ┌─────────────┐
              │ revise(prev,│
              │  feedback)  │──── loop ──▶ critique …
              └─────────────┘
```

The best output scored across all iterations is always retained and returned even if
`maxIterations` is exhausted without convergence.

---

## Usage

**Note**: `ReflectionBehavior` requires a `ReflectionStrategy` and typed lambdas that cannot be expressed via `@JenticBehavior`. Use the builder API and register the behavior manually in `onStart()` or via `addBehavior()`.

### Builder

```java
ReflectionBehavior behavior = ReflectionBehavior.builder("review-report")
    .task("Write a concise summary of the Q3 earnings report")
    .action(() -> agent.generate(task))
    .revise((prev, feedback) ->
        agent.generate(task + "\n\nFeedback from previous attempt:\n" + feedback))
    .strategy(new DefaultReflectionStrategy(llmProvider))
    .config(ReflectionConfig.defaults())          // optional, this is the default
    .onResult(output -> log.info("Final: {}", output))  // optional
    .build();

agent.addBehavior(behavior);
```

### Via LLMAgent.reflect()

For one-off critique without building a full behavior:

```java
String output = callLLM(prompt);
CritiqueResult critique = reflect(output, task).join();

if (critique.shouldRevise()) {
    output = callLLM(prompt + "\nFeedback: " + critique.feedback());
}
```

---

## Configuration

`ReflectionConfig` controls the loop parameters:

```java
// Defaults: 2 iterations, threshold 0.8, built-in critique prompt
ReflectionConfig config = ReflectionConfig.defaults();

// Custom
ReflectionConfig config = new ReflectionConfig(
    3,       // maxIterations
    0.9,     // scoreThreshold — stop early when score >= this
    null     // critiquePrompt — null uses the built-in DefaultReflectionStrategy prompt
);
```

| Parameter | Default | Description |
|-----------|---------|-------------|
| `maxIterations` | `2` | Maximum revise cycles |
| `scoreThreshold` | `0.8` | Early-stop score (inclusive) |
| `critiquePrompt` | `null` | Custom prompt; `null` uses `DefaultReflectionStrategy.DEFAULT_CRITIQUE_PROMPT` |

---

## ReflectionStrategy

The default implementation calls the LLM and parses a `score: X.X` line:

```java
ReflectionStrategy strategy = new DefaultReflectionStrategy(llmProvider);
```

To supply a custom evaluator implement the interface directly:

```java
ReflectionStrategy myEval = (output, task, config) ->
    CompletableFuture.completedFuture(
        output.length() > 200
            ? CritiqueResult.accepted(0.9)
            : CritiqueResult.revise("Output is too short.", 0.4));
```

---

## Reading the Result

```java
agent.addBehavior(behavior);
// ... after behavior execution
String best = behavior.getResult();   // empty string before execution
```

Or use the `onResult` callback to react immediately when the loop completes.

---

## Builder Reference

```java
ReflectionBehavior.builder(String behaviorId)   // named
ReflectionBehavior.builder()                    // auto-generated ID
```

| Method | Required | Description |
|--------|----------|-------------|
| `task(String)` | ✅ | Task description forwarded to the critique prompt |
| `action(Supplier<String>)` | ✅ | Produces the initial output |
| `revise(BiFunction<String,String,String>)` | ✅ | `(prevOutput, feedback) → revisedOutput` |
| `strategy(ReflectionStrategy)` | ✅ | Critique implementation |
| `config(ReflectionConfig)` | — | Defaults to `ReflectionConfig.defaults()` |
| `onResult(Consumer<String>)` | — | Callback invoked with the final best output |

---

## See Also

- [OneShotBehavior](OneShotBehavior.md) - Single execution without reflection
- [Behavior Overview](README.md)
- `dev.agenor.core.reflection.ReflectionStrategy`
- `dev.agenor.core.reflection.ReflectionConfig`
- `dev.agenor.runtime.reflection.DefaultReflectionStrategy`
- `docs/adr/ADR-012-reflection-behavior.md`
