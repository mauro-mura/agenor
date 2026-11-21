# Security Policy

## Supported Versions

Agenor stays on `0.x` (see [ADR-025 §D3](docs/adr/ADR-025-agenor-rebrand.md)). Security fixes
go into the latest minor release only; there are no backports to earlier minors.

| Version              | Supported          |
| -------------------- | ------------------ |
| Latest `0.x` minor   | :white_check_mark: |
| Earlier `0.x` minors | :x:                |

## Reporting a Vulnerability

**Do not report security vulnerabilities through public GitHub issues.**

Email: **info@agenor.dev**

Include:
- Issue type and description
- Steps to reproduce
- Affected source files/versions
- Potential impact

## Security Best Practices

1. **Authentication**: Secure agent communication
2. **Input Validation**: Sanitize message content
3. **Secrets**: Use environment variables for API keys
4. **Dependencies**: Keep updated
5. **Web Console**: Add authentication in production

## Known Considerations

- In-memory messaging: development-only, not production-secured
- LLM keys: store in secret managers
- Web console: no built-in auth (add proxy/gateway)
