# Contributing to Agenor

> **Java meets Agentic.** The enterprise-grade agent framework for the JVM.

Thank you for your interest in contributing to Agenor. This document provides guidelines for contributors and explains how to propose changes, report issues, and get your work merged.

## 🌟 Getting Started

### Prerequisites

- Java 21+ (LTS recommended)
- Maven 3.9+
- Git
- Your favorite Java IDE (IntelliJ IDEA, Eclipse, VS Code)

### Development Setup

1. **Fork and Clone**
```bash
# Fork the repo on GitHub first (via UI), then:
git clone https://github.com/yourusername/jentic.git
cd agenor
# Add upstream to keep your fork in sync
git remote add upstream https://github.com/mauro-mura/jentic.git
```

2. **Build and Verify**
```bash
mvn clean test
mvn clean install
```

3. **IDE Setup**
- Import as Maven project
- Configure code style (see `.editorconfig`)

## 🎯 How to Contribute

### Types of Contributions

1. **Bug Reports** - Help us identify and fix issues
2. **Feature Requests** - Suggest new capabilities
3. **Code Contributions** - Implement features or fix bugs
4. **Documentation** - Improve docs, examples, guides
5. **Testing** - Add tests, improve coverage
6. **Performance** - Optimize existing code

### Before You Start

1. **Check existing issues** - Avoid duplicate work
2. **Create an issue** - Discuss major changes first
3. **Read ADRs** - Understand architectural decisions
4. **Follow patterns** - Study existing code style

## 🔄 Development Workflow

### 1. Create a Branch

```bash
git checkout -b feature/your-feature-name
# or
git checkout -b bugfix/issue-number
# or
git checkout -b docs/improvement-topic
```

### Branch Naming Convention

- `feature/` - New features
- `bugfix/` - Bug fixes
- `docs/` - Documentation changes
- `refactor/` - Code refactoring
- `test/` - Test improvements
- `chore/` - Maintenance tasks

### 2. Make Changes

Follow our coding standards:

```java
// Good: Clear, concise, with proper documentation
@Agent("weather-collector")
public class WeatherCollectorAgent extends BaseAgent {
    
    private final WeatherService weatherService;
    
    public WeatherCollectorAgent(WeatherService weatherService) {
        this.weatherService = requireNonNull(weatherService);
    }
    
    /**
     * Collects weather data every 30 seconds
     */
    @Behavior(type = CYCLIC, interval = "30s")
    public void collectWeatherData() {
        var data = weatherService.getCurrentWeather();
        
        messageService.send(Message.builder()
            .topic("weather.data")
            .content(data)
            .build());
    }
}
```

### 3. Write Tests

All new code should include tests:

```java
@Test
void shouldSendWeatherDataPeriodically() {
    // Given
    var mockWeatherService = mock(WeatherService.class);
    var weatherData = new WeatherData("sunny", 25.0);
    when(mockWeatherService.getCurrentWeather()).thenReturn(weatherData);
    
    var agent = new WeatherCollectorAgent(mockWeatherService);
    
    // When
    agent.collectWeatherData();
    
    // Then
    verify(messageService).send(argThat(message -> 
        "weather.data".equals(message.topic()) && 
        weatherData.equals(message.content())
    ));
}
```

### 4. Update Documentation

- Update README if needed
- Add/update JavaDoc for public APIs
- Create/update examples
- Update architectural docs if applicable

### 5. Commit Changes

Follow conventional commit format:

```bash
git commit -m "feat(core): add weather collector agent

- Implements periodic weather data collection
- Adds configurable collection interval
- Includes comprehensive tests

Resolves #123"
```

**Commit Types:**
- `feat`: New feature
- `fix`: Bug fix
- `docs`: Documentation changes
- `style`: Code style changes (formatting, etc.)
- `refactor`: Code refactoring
- `test`: Adding or updating tests
- `chore`: Maintenance tasks

### 6. Submit Pull Request

1. **Push your branch**
```bash
git push origin feature/your-feature-name
```

2. **Create Pull Request**
- Use clear, descriptive title
- Reference related issues
- Describe what changed and why
- Add screenshots if UI-related

3. **PR Template**
```markdown
## Description
Brief description of changes

## Type of Change
- [ ] Bug fix
- [ ] New feature
- [ ] Breaking change
- [ ] Documentation update

## Testing
- [ ] Tests pass locally
- [ ] New tests added
- [ ] Manual testing completed

## Checklist
- [ ] Code follows style guidelines
- [ ] Self-review completed
- [ ] Documentation updated
- [ ] No breaking changes (or marked as such)

Resolves #issue-number
```

## 📋 Code Standards

### Java Style Guide

1. **Formatting**
   - Use 4 spaces for indentation
   - Max line length: 100 characters
   - Follow Google Java Style Guide

2. **Naming Conventions**
   - Classes: `PascalCase`
   - Methods/Variables: `camelCase`
   - Constants: `UPPER_SNAKE_CASE`
   - Packages: `lowercase.separated.by.dots`

3. **Best Practices**
   - Use `var` for local variables when type is obvious
   - Prefer composition over inheritance
   - Use Java 21 features (records, pattern matching, virtual threads)
   - Write defensive code with null checks
   - Use `CompletableFuture` for async operations

### Architecture Principles

1. **Interface-First Design**
   - Define contracts before implementations
   - Keep interfaces stable
   - Use adapter pattern for extensibility

2. **Dependency Injection**
   - Constructor injection preferred
   - Use `@Nullable` annotations when appropriate
   - Avoid static dependencies

3. **Error Handling**
   - Use specific exception types
   - Provide meaningful error messages
   - Log at appropriate levels

4. **Testing**
   - Unit tests for business logic
   - Integration tests for component interaction
   - Mock external dependencies

## 🧪 Testing Guidelines

### Test Structure

```java
class WeatherCollectorAgentTest {
    
    @Test
    void shouldCollectWeatherData() {
        // Given - setup test data and mocks
        
        // When - execute the code under test
        
        // Then - verify the results
    }
}
```

### Test Categories

1. **Unit Tests** (`*Test.java`)
   - Test individual methods/classes
   - Fast execution (< 1s)
   - No external dependencies

2. **Integration Tests** (`*IT.java`)
   - Test component interactions
   - Can use embedded databases/brokers
   - Slower execution acceptable

3. **Example Tests**
   - Verify examples compile and run
   - Serve as documentation
   - Test end-to-end scenarios

### Test Coverage

- Maintain > 80% line coverage
- Focus on business logic
- Don't test trivial getters/setters
- Test edge cases and error conditions

## 📚 Documentation Standards

### JavaDoc

```java
/**
 * Service for collecting weather data from external APIs.
 * 
 * <p>This service handles rate limiting and error recovery
 * automatically. All methods are thread-safe.
 * 
 * @since 1.0.0
 */
public class WeatherService {
    
    /**
     * Gets current weather for the specified location.
     * 
     * @param location the location to get weather for, not null
     * @return the current weather data
     * @throws WeatherServiceException if weather data cannot be retrieved
     * @throws IllegalArgumentException if location is null or empty
     */
    public WeatherData getCurrentWeather(String location) {
        // implementation
    }
}
```

### README Updates

- Keep examples up-to-date
- Update feature lists
- Maintain accurate installation instructions
- Include migration notes for breaking changes

## 🚀 Release Process

### Version Numbering

We follow [Semantic Versioning](https://semver.org/):
- **MAJOR**: Breaking changes
- **MINOR**: New features, backwards compatible
- **PATCH**: Bug fixes, backwards compatible

### Pre-release Checklist

- [ ] All tests pass
- [ ] Documentation updated
- [ ] Examples verified
- [ ] Performance benchmarks run
- [ ] Migration guide updated (if needed)

## 🤝 Community Guidelines

### Code of Conduct

- Be respectful and inclusive
- Focus on constructive feedback
- Help newcomers learn
- Celebrate contributions of all sizes

### Communication

- **Issues**: Bug reports, feature requests
- **Discussions**: General questions, ideas
- **Discord**: Real-time community chat
- **Email**: Private/sensitive matters

### Getting Help

1. **Check Documentation** - README, examples
2. **Search Issues** - Someone might have had the same problem
3. **Ask in Discussions** - Community can help
4. **Create an Issue** - If you found a bug or have a specific request

## 🤖 Development with AI Assistance

This project has been developed in collaboration with Claude AI.
Many features, implementations, and architectural decisions have been
shaped through AI-assisted development, code generation, and design
discussions.

## 📧 Contact

- **Project Lead**: Mauro Mura
- **Issues**: [GitHub Issues](https://github.com/mauro-mura/jentic/issues)

Thank you for helping make Agenor better! 🚀
