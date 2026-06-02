# FinanceCloud Support Chatbot

Multi-agent support chatbot for personal finance app featuring:
- Intent routing with sentiment analysis
- Multi-turn context tracking
- Human escalation
- LLM integration with RAG pattern
- TF-IDF + Embeddings hybrid search
- Query expansion with synonyms
- File-based persistence
- Analytics & Metrics
- Multi-language support (6 languages)
- Rate limiting
- **A2A Protocol support**
- **Collaborative Reasoning (multi-agent synthesis)**

## Structure

```
support/
├── SupportChatbotExample.java
├── agents/
│   ├── RouterAgent.java              # Simple routing
│   ├── CollaborativeRouterAgent.java # ★ Multi-agent synthesis
│   ├── ConsultableAgent.java         # ★ Interface for consultation
│   ├── FAQAgent.java                 # Consultable
│   ├── AccountAgent.java             # Consultable
│   ├── TransactionAgent.java         # Consultable
│   ├── SecurityAgent.java            # Consultable
│   ├── BudgetAgent.java              # Consultable
│   └── EscalationAgent.java
├── a2a/
│   ├── A2AHttpServer.java
│   ├── SupportA2AServer.java
│   └── SupportAgentCardConfig.java
├── context/
│   ├── ConversationContext.java
│   ├── ConversationContextManager.java
│   └── SentimentAnalyzer.java
├── llm/
│   ├── LLMConfig.java
│   ├── LLMResponseGenerator.java
│   └── PromptTemplates.java
├── knowledge/
│   └── ... (10 files)
├── production/
│   └── ... (5 files)
├── model/
│   └── ... (3 files)
└── service/
    └── MockUserDataService.java
```

## Run Modes

```bash
# Simple routing (default)
mvn exec:java

# Collaborative multi-agent mode
mvn exec:java -Dexec.args="--collab"

# A2A server
mvn exec:java -Dexec.args="--a2a"

# Collaborative + A2A server
mvn exec:java -Dexec.args="--collab --a2a 8081"

# Demo mode
mvn exec:java -Dexec.args="demo"
```

## Collaborative Reasoning

The `CollaborativeRouterAgent` consults multiple agents in parallel and synthesizes responses:

```
User Query: "What's my balance and is my account secure?"
        │
        ▼
┌─────────────────────────────────────────────────────┐
│           CollaborativeRouterAgent                  │
│                                                     │
│  Parallel consultation:                             │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐         │
│  │ FAQAgent │  │ Account  │  │ Security │         │
│  │          │  │  Agent   │  │  Agent   │         │
│  └────┬─────┘  └────┬─────┘  └────┬─────┘         │
│       │ conf:0.4    │ conf:0.9    │ conf:0.7      │
│       ▼             ▼             ▼               │
│  ┌─────────────────────────────────────────────┐  │
│  │         Response Synthesizer                │  │
│  │  - Primary: AccountAgent (highest conf)     │  │
│  │  - Enriched with SecurityAgent insights     │  │
│  └─────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────┘
        │
        ▼
"Your balance is $1,234.56. Your account has 2FA enabled
 and no suspicious activity detected.
 
 Additionally:
 • Last login from known device
 • No password changes in 30 days"
```

### ConsultableAgent Interface

Agents implement `ConsultableAgent` to participate in collaborative reasoning:

```java
public interface ConsultableAgent {
    AgentContribution consult(AgentConsultation consultation);
    List<SupportIntent> getExpertise();
}
```

Each contribution includes:
- `responseText` - agent response
- `confidence` - score 0-1
- `intent` - handled intent
- `insights` - additional info/suggested actions

### Consultable Agents

| Agent | Expertise | Status |
|-------|-----------|--------|
| FAQAgent | FAQ, GENERAL | ✅ Consultable |
| AccountAgent | ACCOUNT_INFO, BILLING | ✅ Consultable |
| SecurityAgent | SECURITY, PASSWORD_RESET | ✅ Consultable |
| TransactionAgent | TRANSACTION_HISTORY, BILLING | ✅ Consultable |
| BudgetAgent | BUDGET, ACCOUNT_INFO | ✅ Consultable |

### Example Output (Collaborative)

```
You: What's my balance and is my account secure?

┌─ Bot (ACCOUNT_INFO, confidence: 85%) ─┐
Current balance: $1,234.56 (Premium plan)

Security status: 2FA enabled, 3 trusted devices
Last login: 2 hours ago from Chrome on Windows

Additionally:
• Last password change: 45 days ago
• No suspicious activity detected
• Consider enabling login notifications

[Suggested: View transactions | Security settings | Speak to human]
[Contributors: [account-agent, security-agent, faq-agent]]
└────────────────────────────────────────────┘
```

## A2A Integration

### Agent Card

The chatbot exposes an `AgentCard` with 6 skills:

| Skill | Description |
|-------|-------------|
| `support-query` | General query with intent classification |
| `account-info` | Balance, profile, linked accounts |
| `transaction-history` | History, export, disputes |
| `security-help` | Password, 2FA, devices |
| `budget-management` | Budget tracking and alerts |
| `escalate` | Human agent connection |

### Endpoints

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/.well-known/agent.json` | GET | Agent discovery (AgentCard) |
| `/a2a` | POST | JSON-RPC message handling |
| `/health` | GET | Health check |

### Test with curl

```bash
# Get AgentCard
curl http://localhost:8081/.well-known/agent.json

# Send A2A message
curl -X POST http://localhost:8081/a2a \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc": "2.0",
    "id": "1",
    "method": "message/send",
    "params": {
      "message": {
        "parts": [{"type": "text", "text": "How do I reset my password?"}]
      }
    }
  }'
```

### A2AHttpServer API

```java
A2AHttpServer server = A2AHttpServer.builder()
    .port(8081)
    .messageService(runtime.getMessageService())
    .timeout(Duration.ofMinutes(2))
    .build();

server.start().join();
```

## Production Features

### 1. Persistence

File-based conversation storage:

```java
ConversationRepository repo = new ConversationRepository(
    Path.of("~/.financecloud/conversations"));

repo.saveTurn(sessionId, query, response, responseTimeMs);
repo.recordSatisfaction(sessionId, 5, "Great help!");
```

### 2. Analytics

Real-time metrics:

```java
AnalyticsService analytics = new AnalyticsService();
analytics.recordQuery(sessionId, intent, responseTimeMs, confidence);

OverallStats stats = analytics.getOverallStats();
System.out.println(analytics.generateReport());
```

### 3. Multi-Language Support

**Supported:** English, Italian, Spanish, French, German, Portuguese

```java
LanguageDetector detector = new LanguageDetector();
DetectionResult result = detector.detect("Come posso reimpostare la password?");
// → Language.ITALIAN, confidence: 0.85

LocalizationService l10n = new LocalizationService();
String welcome = l10n.getWelcomeMessage(Language.ITALIAN);
```

### 4. Rate Limiting

Sliding window + token bucket protection:

```java
RateLimiter limiter = new RateLimiter(60, Duration.ofMinutes(1), 10, Duration.ofSeconds(1));

RateLimitResult result = limiter.checkLimit(clientId);
if (!result.isAllowed()) {
    // Reject with retry-after header
}
```

## File Count

**38 Java files** total:
- 9 agents (+ CollaborativeRouterAgent, ConsultableAgent)
- 3 a2a
- 3 context
- 3 llm
- 10 knowledge
- 5 production
- 3 model
- 1 service
- 1 main

## Architecture

```
User Query
    │
    ▼
┌─────────────────────────────────────────────────────────────────┐
│ RouterAgent / CollaborativeRouterAgent                          │
│  1. RateLimiter.checkLimit(sessionId)                           │
│  2. LanguageDetector.detect(query)                              │
│  3. SentimentAnalyzer.analyze(query)                            │
│  4. classifyIntent(query)                                       │
│  5. Route to specialized agent(s)                               │
└─────────────────────────────────────────────────────────────────┘
    │
    ▼
[Specialized Agents] → support.response
    │
    ▼
┌─────────────────────────────────────────────────────────────────┐
│ Response Tracking                                               │
│  1. AnalyticsService.recordQuery(session, intent, time, conf)   │
│  2. ConversationRepository.saveTurn(session, query, response)   │
└─────────────────────────────────────────────────────────────────┘
```

## DialogueCapability Usage

```java
@JenticAgent("my-agent")
public class MyAgent extends BaseAgent implements ConsultableAgent {
    
    private final DialogueCapability dialogue = new DialogueCapability(this);
    
    @Override
    protected void onStart() {
        dialogue.initialize(getMessageService());
    }
    
    @DialogueHandler(performatives = {Performative.QUERY})
    public void handleConsultation(DialogueMessage msg) {
        AgentContribution contribution = consult(msg.contentAs(AgentConsultation.class));
        dialogue.reply(msg, Performative.INFORM, contribution);
    }
    
    @Override
    public AgentContribution consult(AgentConsultation consultation) {
        return new AgentContribution(getAgentId(), "response", 0.8, 
            SupportIntent.FAQ, List.of("insight1"));
    }
}
```
