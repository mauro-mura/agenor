# Guardrails Guide

The Guardrails Layer (ADR-014) provides declarative input/output validation for
`LLMAgent` subclasses. Guardrails intercept content at two points in the pipeline:

```
User input
  → InputGuardrailChain   (PII redaction, token limit, content policy)
  → LLMProvider.chat()
  → OutputGuardrailChain  (schema validation, content policy, safety filter)
  → Consumer
```

---

## Available guardrails

| Class | Direction | Description |
|-------|-----------|-------------|
| `PiiRedactionGuardrail` | Input + Output | Detects and redacts PII (email, phone IT, CF, IBAN, credit card) |
| `ContentPolicyGuardrail` | Input + Output | Blocks content matching a YAML blocklist (patterns + topics) |
| `JsonSchemaOutputGuardrail` | Output | Validates JSON output against a schema; re-prompts on violation |
| `MaxTokensInputGuardrail` | Input | Truncates input that exceeds a token budget (3 strategies) |

---

## GuardrailChain builder

Build a chain programmatically when guardrails need constructor parameters:

```java
GuardrailChain chain = GuardrailChain.builder()
    .addInput(new PiiRedactionGuardrail())
    .addInput(new MaxTokensInputGuardrail(2048))
    .addOutput(new ContentPolicyGuardrail("/etc/jentic/policy.yaml"))
    .build();

agent.setGuardrailChain(chain);
```

Execution semantics:

| Result | Behaviour |
|--------|-----------|
| `Passed` | Content unchanged, next guardrail receives same value |
| `Modified(newContent)` | Content replaced, chain continues with new value |
| `Blocked(reason)` | Chain short-circuits, `GuardrailViolationException` thrown |

---

## `@WithGuardrails` annotation

Declare guardrails on the agent class. `JenticRuntime` instantiates them via their
public no-arg constructor and injects the chain at registration time:

```java
@JenticAgent("finance-agent")
@WithGuardrails(
    input  = { PiiRedactionGuardrail.class, MaxTokensInputGuardrail.class },
    output = { ContentPolicyGuardrail.class }
)
public class FinanceAgent extends LLMAgent { ... }
```

> **Note** — guardrails listed in `@WithGuardrails` must expose a **public no-arg constructor**.
> For guardrails that need parameters (e.g. a YAML path), use the `GuardrailChain` builder instead.

Combining both approaches is supported: the annotation-derived chain is **prepended** to any
programmatic chain already set on the agent.

---

## PiiRedactionGuardrail

Detects and replaces PII tokens with `[REDACTED]`.

```java
// All PII types (default)
new PiiRedactionGuardrail()

// Selective
new PiiRedactionGuardrail(EnumSet.of(PiiType.EMAIL, PiiType.IBAN))
```

| `PiiType` | Pattern |
|-----------|---------|
| `EMAIL` | Standard email addresses |
| `PHONE_IT` | Italian mobile numbers (+39 prefix optional) |
| `CODICE_FISCALE` | 16-char Italian fiscal code (case-insensitive) |
| `IBAN` | International Bank Account Number |
| `CREDIT_CARD` | 13–19 digit card numbers (spaces/dashes optional) |

Returns `Modified(redactedContent)` if at least one match is found, `Passed` otherwise.

---

## ContentPolicyGuardrail

Loads a YAML blocklist and blocks matching content.

```java
new ContentPolicyGuardrail("/etc/jentic/policy.yaml")
new ContentPolicyGuardrail("classpath:guardrails/policy.yaml")
```

YAML format:

```yaml
content-policy:
  blocked-patterns:
    - pattern: "(?i)guaranteed return"
      reason:  "Prohibited financial promise"
  blocked-topics:
    - "violence"
    - "buy*now"          # wildcard supported
```

`blocked-patterns` accepts full Java regex (inline flags like `(?i)` are honoured).
`blocked-topics` are matched as case-insensitive substrings; `*` is the only wildcard.

---

## JsonSchemaOutputGuardrail

Validates LLM JSON output against a JSON Schema (Draft 7 subset: `type`, `required`,
`properties`, `items`).

```java
String schema = """
    {
      "type": "object",
      "required": ["name", "score"],
      "properties": {
        "name":  { "type": "string" },
        "score": { "type": "number" }
      }
    }
    """;

// Default: 1 re-prompt attempt before blocking
new JsonSchemaOutputGuardrail(schema)

// Custom attempt cap
new JsonSchemaOutputGuardrail(schema, 2)
```

| Situation | Result |
|-----------|--------|
| Valid JSON, schema ok | `Passed` |
| Non-JSON output | `Blocked` (immediate) |
| Schema violation, attempts remaining | `Modified(repromptInstruction)` |
| Schema violation after max attempts | `Blocked` |

Call `guardrail.resetAttempts()` between agent invocations to allow re-prompting again.

---

## MaxTokensInputGuardrail

Truncates inputs that exceed a token budget.

```java
// Default: END strategy, SimpleTokenEstimator
new MaxTokensInputGuardrail(512)

// Custom strategy
new MaxTokensInputGuardrail(1024, TruncationStrategy.MIDDLE)

// Custom estimator
new MaxTokensInputGuardrail(1024, TruncationStrategy.END, myEstimator)
```

| `TruncationStrategy` | Behaviour |
|---------------------|-----------|
| `END` (default) | Keep beginning, drop tail |
| `START` | Keep end, drop head |
| `MIDDLE` | Keep beginning and end, drop centre |

---

## Writing a custom guardrail

Implement `InputGuardrail` or `OutputGuardrail` (both are `@FunctionalInterface`):

```java
// Lambda form
InputGuardrail maxLength = (input, ctx) -> {
    if (input.length() > 4096) {
        return CompletableFuture.completedFuture(
                new GuardrailResult.Blocked("Input too long"));
    }
    return CompletableFuture.completedFuture(new GuardrailResult.Passed());
};

// Class form (required for @WithGuardrails — must have no-arg constructor)
public class MaxLengthGuardrail implements InputGuardrail {
    @Override
    public CompletableFuture<GuardrailResult> apply(String input, GuardrailContext ctx) {
        return CompletableFuture.completedFuture(
            input.length() > 4096
                ? new GuardrailResult.Blocked("Input too long")
                : new GuardrailResult.Passed());
    }
}
```

`GuardrailContext` carries `agentId`, `topic`, and an open `metadata` map for
passing contextual information from the agent to its guardrails.

---

## Handling `GuardrailViolationException`

`GuardrailViolationException` extends `JenticException` (unchecked). Catch it
explicitly when you need to react to policy violations:

```java
try {
    String response = agent.ask(userInput);
} catch (GuardrailViolationException e) {
    log.warn("Guardrail [{}] blocked content: {}", e.blockedBy(), e.reason());
    return "Your request could not be processed due to content policy.";
}
```

---

## Running the example

```bash
mvn exec:java -pl jentic-examples \
    -Dexec.mainClass=dev.jentic.examples.GuardrailsExample
```

No API key required — the example uses a stub LLM provider.

---

## See also

- `ADR-014` — architectural decision: sealed interface vs enum, hook position in `LLMAgent`
- `GuardrailChainTest` — chain execution semantics (pass, modify, block, concurrency)
- `PiiRedactionGuardrailTest` — PII pattern coverage and false-positive rate
- `ContentPolicyGuardrailTest` — YAML loading, wildcard matching
