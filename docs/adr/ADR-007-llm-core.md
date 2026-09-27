# ADR-007: LLMProvider as Core Interface

## Status
Accepted

## Context
Integration of Agentic AI capabilities requires LLM provider abstraction.

## Decision
LLMProvider will be a core interface in agenor-core with implementations
in agenor-adapters, following the same pattern as MessageService and
AgentDirectory.

## Consequences
- Positive: Clean separation, user choice, no vendor lock-in
- Positive: Follows established framework patterns
- Negative: Slightly more complex module structure (mitigated by consistency)

---

## Amendment — 2026-09-27: function calling is a round trip, not a one-way message

**Status**: Accepted. Amends the Decision section.

### What this fills

The original decision said nothing about the shape of function calling beyond `LLMMessage`'s
`FUNCTION` role and the `chat`/`chatStream` contract on `LLMProvider`. In practice only the
outbound half worked: `FunctionCall` has carried an `id` since this ADR shipped, but
`LLMMessage`'s `FUNCTION`-role factory never captured it, so a result could never be paired back
to the call it answered. OpenAI and Anthropic degraded a tool result to an ordinary user message
with no `tool_call_id`; Anthropic's server-side pairing requirement turned that into a hard
failure, not a degraded one.

### D-1 — `LLMMessage` carries the id of the call it answers

`LLMMessage` gains a fifth record component, `functionCallId`, populated by a new
`LLMMessage.function(FunctionCall, String)` factory. The existing two-argument
`function(String, String)` is deprecated for removal at `1.0.0`, since it has no way to carry an
id. This is `BREAKING` on `agenor-core`: the canonical constructor's arity changes.

OpenAI and Anthropic map a `FUNCTION`-role message to LangChain4j's
`ToolExecutionResultMessage(id, name, content)`, and an `ASSISTANT` message's function calls to
its `AiMessage`'s tool execution requests. A `FUNCTION`-role message with no id is rejected with
`LLMException` `INVALID_REQUEST` before it reaches the client, rather than silently degraded.

### What this does not decide

No tool loop. Nothing in the runtime executes a function call and feeds the result back to the
model automatically — that is a different, larger decision this amendment does not make.

Ollama's message conversion now rejects a `FUNCTION`-role message with
`LLMException.unsupportedOperation` instead of a generic `IllegalArgumentException`;
`supportsFunctionCalling()` already reported `false` there, so this is a clearer failure at an
existing boundary, not a new one.