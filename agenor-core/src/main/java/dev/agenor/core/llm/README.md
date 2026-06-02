# dev.agenor.core.llm

> This is the package README. For the complete LLM integration guide see
> [`docs/llm-integration.md`](../../../../../../../../docs/llm-integration.md).

Provider-agnostic LLM abstraction layer for Jentic agents. Defines interfaces and
value types only — no external dependencies beyond the Java standard library.
Concrete provider implementations (OpenAI, Anthropic, Ollama) live in `agenor-adapters`.

## Package contents

| Class | Role |
|-------|------|
| `LLMProvider` | Core interface: `chat()`, `chatStream()`, `getAvailableModels()`, `supportsFunctionCalling()`, `supportsStreaming()` |
| `LLMRequest` | Immutable request record (builder) |
| `LLMResponse` | Response record: content, usage stats, function calls |
| `LLMMessage` | Single conversation turn: `system()`, `user()`, `assistant()`, `function()` |
| `StreamingChunk` | Incremental chunk delivered to the streaming handler |
| `FunctionDefinition` | Tool/function schema the LLM may call (builder) |
| `FunctionCall` | Function invocation requested by the LLM in a response |
| `LLMException` | Typed exception with `ErrorType`: `RATE_LIMIT`, `AUTHENTICATION`, `CONTEXT_LENGTH_EXCEEDED`, `NETWORK`, `QUOTA_EXCEEDED`, `INVALID_REQUEST`, `MODEL_NOT_FOUND`, `PARSE_ERROR`, `SERVER_ERROR`, `CONTENT_FILTERED`, `UNSUPPORTED_OPERATION`, `UNKNOWN` |

## See Also

- [`docs/llm-integration.md`](../../../../../../../../docs/llm-integration.md) — full guide: providers, requests, streaming, function calling, `LLMAgent`, memory strategies, error handling, testing
- [`agenor-adapters/README.md`](../../../../../../../../agenor-adapters/README.md) — `LLMProviderFactory` and provider configuration
