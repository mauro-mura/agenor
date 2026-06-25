# Security Policy

## Supported Versions

| Version  | Supported          |
| -------- | ------------------ |
| 1.0.x    | :white_check_mark: |
| < 1.0.0  | :x:                |

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
