# ADR-011: KnowledgeStore as Core Interface (RAG Support)

**Status**: Accepted
**Date**: 2026-03-05  
**Authors**: Project Team

## Context
RAG (Retrieval-Augmented Generation) is a foundational pattern for LLM-powered agents.
A `KnowledgeStore` implementation already existed in `agenor-examples/support/knowledge`,
but was tightly coupled to a domain-specific category type (`SupportIntent`) and not
reusable outside that example.

Agents that need a knowledge base had no framework-level abstraction to depend on,
forcing them to either copy the example code or implement their own solution.
`LLMProvider` is already a first-class citizen in `agenor-core`; RAG is its natural complement.

## Decision
Promote `KnowledgeStore` to a first-class framework feature following the established
`LLMProvider` pattern:

- **`agenor-core`**: `KnowledgeStore<C>`, `KnowledgeDocument<C>`, `EmbeddingProvider`,
  `EmbeddingException` — pure interfaces and records, zero external dependencies.
- **`agenor-runtime`**: `InMemoryKnowledgeStore<C>`, `QueryExpander` — keyword-based
  implementations requiring no external services.
- **`agenor-adapters`**: `OpenAIEmbeddingProvider`, `OllamaEmbeddingProvider`,
  `EmbeddingProviderFactory` — HTTP-backed embedding providers, mirroring the
  `llm/` adapter structure.

The category type is generalised via a type parameter `<C>` (previously hardcoded to
`SupportIntent`). Domain-specific stores (`SemanticKnowledgeStore`, `HybridKnowledgeStore`)
and seed data remain in `agenor-examples`, parameterised as `KnowledgeStore<SupportIntent>`.

A potential `RAGAgent` (Level 2) is deferred until the Level 1 interfaces stabilise
in production use.

## Alternatives Considered

- **Leave in examples**: Zero framework cost, but forces copy-paste for every RAG use case.
  Rejected — the demand is clear and the generalisation effort is low.
- **External library (LangChain4j, Spring AI)**: Richer feature set but introduces a
  heavyweight dependency into `agenor-core`. Rejected — conflicts with ADR-002
  (interface-first, minimal deps) and ADR-004 (progressive complexity).
- **Promote with RAGAgent immediately**: Higher scope, risk of premature abstraction.
  Deferred — Level 1 alone provides value without coupling agent lifecycle to retrieval.

## Consequences
- **Positive**: Clean RAG abstraction reusable across any agent, no vendor lock-in.
- **Positive**: Consistent with `LLMProvider` pattern — familiar to existing contributors.
- **Positive**: `InMemoryKnowledgeStore` works out of the box without any external service.
- **Negative**: Two additional packages in `agenor-core` and `agenor-runtime`
  (mitigated by small surface area and clear naming).
- **Negative**: `agenor-examples` support package requires import updates
  (mechanical, no logic changes).
