# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added
- **Spring Boot Starter (ADR-016)**: Introduced `jentic-spring-boot-starter` for seamless integration with Spring Boot applications.
  - Auto-configuration for `JenticRuntime` based on classpath scanning and configuration properties.
  - Support for `JenticProperties` to configure agent packages, LLM providers, and memory settings.
  - Dedicated documentation guide for Spring Boot Starter in `docs/spring-boot-starter.md`.

### Changed
- **Documentation**: Standardized documentation headers and formatting across core and runtime packages for better consistency.
- **Project Structure**: Updated parent `pom.xml` and module-specific configurations to include the new Spring Boot Starter module.

## [0.13.0] - 2026-03-25

### Added
- **Human-in-the-Loop Checkpoint (ADR-015)**: Introduced a mechanism to pause agent execution and wait for human approval before proceeding.
  - Core abstractions in `jentic-core`: `ApprovalRequest`, `ApprovalDecision`, `ApprovalGate`, `ApprovalNotifier`, and `ApprovalTimeoutException`.
  - `@RequiresApproval` annotation for declarative wiring of human checkpoints to agents.
  - Implementation in `jentic-runtime`: `ApprovalService` to manage the lifecycle of approval requests.
  - Built-in `ApprovalGate` implementations: `InMemoryApprovalGate` for local testing.
  - Built-in `ApprovalNotifier` implementations: `WebhookApprovalNotifier` for remote notifications and `LoggingApprovalNotifier`.
  - New dedicated guide for Human-in-the-Loop and `HumanCheckpointBehaviorTest` in `jentic-runtime`.
- **Guardrails Layer (ADR-014)**: Introduced a declarative interceptor chain for `LLMAgent` to validate and transform inputs and outputs.
  - Core abstractions in `jentic-core`: `GuardrailResult` (sealed interface), `InputGuardrail`, `OutputGuardrail`, and `GuardrailContext`.
  - `GuardrailChain` in `jentic-runtime` for sequential execution and short-circuiting on violations.
  - `@WithGuardrails` annotation for declarative wiring of guardrails to agents.
  - Built-in implementations: `PiiRedactionGuardrail`, `ContentPolicyGuardrail`, `MaxTokensInputGuardrail`, and `JsonSchemaOutputGuardrail`.
  - New dedicated guide for Guardrails and `GuardrailsExample` in `jentic-examples`.
- **Model Context Protocol (MCP) Integration (ADR-013)**: Support for official MCP SDK to connect external tools to LLM workflows.
  - `JenticMcpClientAdapter` and `McpClientFactory` for synchronous to asynchronous SDK bridging.
  - `McpToolRegistry` with TTL support for efficient tool caching and discovery.
  - `McpFunctionAdapter` to map MCP tools to Jentic function-calling framework.
  - Core abstractions: `McpClient`, `McpTool`, and `McpToolResult` in `jentic-core`.
- **MCP Documentation**: Detailed guide for MCP adapter and architecture overview in `docs/adapters/mcp.md`.
- **MCP Example**: `McpExample` demonstrating Docker-based STDIO transport for MCP servers in `jentic-examples`.
- **Branding Assets**: Added official Jentic logo and wordmark to `docs/assets`.

### Changed
- **Documentation**: Significant refactoring of the documentation structure, including updated ADRs (ADR-002, ADR-004, ADR-015) and a simplified `README.md` with removed outdated roadmap.
- **Project Structure**: Updated ADR documentation with ADR-013, ADR-014, ADR-015 and expanded `mkdocs.yml` navigation for MCP, Guardrails and HITL support.

### Fixed
- **Documentation**: Fixed version annotation in `ReflectionBehavior.md`.

## [0.12.0] - 2026-03-14

### Added
- **Reflection Pattern (ADR-012)**: Introduced `ReflectionStrategy` and `ReflectionBehavior` for the Generate → Critique → Revise loop.
  - `ReflectionStrategy`, `CritiqueResult`, and `ReflectionConfig` added to `jentic-core` as core abstractions.
  - `DefaultReflectionStrategy` and `ReflectionBehavior` added to `jentic-runtime` for LLM-backed self-critique.
- **Reflection Example**: Added `ReflectionExample` demonstrating the self-correction loop in `jentic-examples`.
- **Documentation**: New dedicated guide for `ReflectionBehavior` and updated `mkdocs.yml` navigation.

### Changed
- **Project Structure**: Expanded ADR documentation with ADR-012 and updated README with Support and Development sections.

## [0.11.0] - 2026-03-11

### Added
- **Configuration-driven package scanning**: `JenticRuntime` now uses `getAllScanPackages()` from configuration for agent discovery.

### Changed
- **Configuration Guide**: Clarified builder method behavior and unchecked `ConfigurationException` handling in documentation.
- **Exception Hierarchy (BREAKING CHANGE)**: Restructured all core exceptions (LLM, Persistence, Memory, Embedding) to inherit from `JenticException` (a `RuntimeException`) and moved them to their respective functional packages (e.g., `dev.jentic.core.persistence`).
- **Configuration Loading**: Simplified `ConfigurationLoader` API by removing explicit checked `ConfigurationException` from `loadFromFile`.
- **Validation Logic**: Improved configuration validation in `JenticRuntime.Builder`, ensuring invalid configurations are caught early.

### Fixed
- **Documentation Workflows**: Fixed table formatting in ADR index and link formatting in documentation deployment workflows.

## [0.10.0] - 2026-03-07

### Added
- **`LLMMemoryAware` interface** in `dev.jentic.core.llm`: marker interface that allows any `Agent` implementor (including those that cannot extend `LLMAgent`) to receive an injected `LLMMemoryManager` from the runtime. `LLMAgent` now implements this interface; `JenticRuntime` injects via `LLMMemoryAware` instead of `instanceof LLMAgent`.
- **`AgentContext` support** for plain `Agent` implementations and improved runtime agent creation.
- **LLM-based summarization** in `SummarizationStrategy` for context window management.
- Promotion of `KnowledgeStore` and `EmbeddingProvider` from adapters to core/runtime for broader availability.

### Fixed
- Increased timing thresholds in `ParallelBehaviorTest` and `SequentialBehaviorTest` for CI reliability.
- Use of dedicated `CachedThreadPool` in test behaviors to prevent ForkJoinPool starvation on CI.

### Changed
- Update of LLM integration guide with new summarization and knowledge store features.
- Updated Logback to version `1.5.32`.
- Updated AssertJ to version `3.27.7`.

## [0.9.0] - 2026-03-04

### Added
- "Getting Started" guide and documentation index.
- GitHub Actions workflow for automatic documentation deployment.
- Test coverage for `jentic-adapters` module.
- Support for detailed Javadoc annotations and usage examples in Jentic annotations.

### Fixed
- Synchronization in `ratelimit` to prevent limit overruns in concurrency scenarios.
- Broken links in README.md file.
- Path normalization in documentation deployment workflow.

### Changed
- Optimization of Maven Javadoc configuration.
- Standardization of link formatting throughout documentation.

## [0.8.0] - 2026-02-28

### Added
- Complete documentation for all behavior types.
- README "Learning Path" for `jentic-examples` module.

### Changed
- Refactoring of examples for a more linear structure and pattern-oriented naming.
- Replacement of `ConfigurationLoader` class with a cleaner interface.
- Improvement of `SimpleBehaviorScheduler` to handle additional behavior types.

### Fixed
- Correction of thresholds in system conditions (CPU usage).
- Simplification of agent registration in `BatchProcessing` example.

## [0.7.1] - 2026-02-24

### Fixed
- Improvement of `STOPPING` state validation in `LifecycleManagerTest`.
- More robust handling of asynchronous operations in the agent lifecycle.

## [0.7.0] - 2026-02-22

### Added
- **Bill of Materials (BOM)** module for centralized version management.
- Support for **A2A (Agent-to-Agent)** protocol with Jetty/HTTP-based implementation.
- LLM integration pattern: **Orchestrator-Workers**.
- **Support Chatbot** example with RAG (Retrieval-Augmented Generation) and TF-IDF semantic search.
- Support for **Automatic-Module-Name** (JPMS) in all modules.
- Extended test framework with JaCoCo coverage and new unit tests for core, runtime, and adapters.
- Code of Conduct and Security Policy.

### Fixed
- Handling of NaN/negative values in system metrics.
- Race condition in agent registration during startup.
- Various fixes in timing tests (ScheduledBehavior, WakerBehavior).

## [0.6.0] - 2026-02-14

### Added
- **LLM Memory Management** system with automatic context injection.
- Strategies for Context Window management in AI agents.
- `AIAgent` base class to facilitate development of agents with LLM support.
- Integration of `MemoryStore` into `JenticRuntime`.

### Changed
- Moved `LLMMemoryManager` responsibility directly into `LLMAgent`.

## [0.5.0] - 2026-02-07

### Added
- **Agent Evaluation Framework** for agent testing and validation.
- Full implementation of dialogue protocols: **ContractNet, Query, Request**.
- Support for utilities to convert dialogues into A2A messages.
- A2A integration example.

### Changed
- Refactoring of `ContractNet` example to use `JenticRuntime`.

## [0.4.0] - 2025-11-20

### Added
- **Jentic Web Console**: web interface for agent monitoring and management.
- Support for message history storage with dedicated REST API.
- CLI tools for message monitoring and watching.
- `MessageSnifferAgent` for passive traffic monitoring.
- `AIAssistantAgent` example with LLM-based tool execution.

### Fixed
- Reactivation of behaviors after agent restart.
- Resolution of classpath in `AgentScanner` for CLI execution.
- Uptime calculation in `RestAPIHandler`.

## [0.3.0] - 2025-11-04

### Added
- Integration with LLM providers: **OpenAI, Anthropic, and Ollama**.
- Support for streaming, function calling, and LLM request/response logging.
- `ResearchTeam` example with agent collaboration and dynamic discovery.
- `baseUrl` configuration for LLM providers (support for proxies and local LLMs).

### Fixed
- Metadata loss in `AgentDescriptor`.
- NPE in handling null content in `OpenAIProvider`.

## [0.2.0] - 2025-10-27

### Added
- Support for **YAML Configuration**.
- New Behavior types:
  - `BatchBehavior`: batch processing by size or time.
  - `RetryBehavior`: retry strategies with backoff.
  - `CircuitBreakerBehavior`: resilience patterns.
  - `PipelineBehavior`: staged processing.
  - `ScheduledBehavior`: cron-like scheduling.
  - `ThrottledBehavior`: rate limiting (Token Bucket, Sliding Window).
  - `CompositeBehavior`: sequential, parallel, and FSM.
  - `ConditionalBehavior`.
- Support for file-based persistence and lifecycle hooks.
- Advanced message filtering and direct messaging.

## [0.1.1] - 2025-10-18

### Fixed
- Minor documentation fixes (README).

## [0.1.0] - 2025-10-17

### Added
- Initial release of Jentic framework.
- Core abstractions for Agents and Behaviors.
- `JenticRuntime` for agent lifecycle management.
- `LifecycleManager` for agent state monitoring.
- Support for agent discovery via annotations.
- ADR-based architecture (Architectural Decision Records).
- Architecture guide and initial documentation.

[Unreleased]: https://github.com/mauro-mura/jentic/compare/v0.13.0...HEAD
[0.13.0]: https://github.com/mauro-mura/jentic/compare/v0.12.0...v0.13.0
[0.12.0]: https://github.com/mauro-mura/jentic/compare/v0.11.0...v0.12.0
[0.11.0]: https://github.com/mauro-mura/jentic/compare/v0.10.0...v0.11.0
[0.10.0]: https://github.com/mauro-mura/jentic/compare/v0.9.0...v0.10.0
[0.9.0]: https://github.com/mauro-mura/jentic/compare/v0.8.0...v0.9.0
[0.8.0]: https://github.com/mauro-mura/jentic/compare/v0.7.1...v0.8.0
[0.7.1]: https://github.com/mauro-mura/jentic/compare/v0.7.0...v0.7.1
[0.7.0]: https://github.com/mauro-mura/jentic/compare/v0.6.0...v0.7.0
[0.6.0]: https://github.com/mauro-mura/jentic/compare/v0.5.0...v0.6.0
[0.5.0]: https://github.com/mauro-mura/jentic/compare/v0.4.0...v0.5.0
[0.4.0]: https://github.com/mauro-mura/jentic/compare/v0.3.0...v0.4.0
[0.3.0]: https://github.com/mauro-mura/jentic/compare/v0.2.0...v0.3.0
[0.2.0]: https://github.com/mauro-mura/jentic/compare/v0.1.1...v0.2.0
[0.1.1]: https://github.com/mauro-mura/jentic/compare/v0.1.0...v0.1.1
[0.1.0]: https://github.com/mauro-mura/jentic/releases/tag/v0.1.0
