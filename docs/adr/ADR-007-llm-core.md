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