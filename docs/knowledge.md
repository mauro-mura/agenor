# Knowledge and embeddings

An agent that answers questions needs somewhere to look. `KnowledgeStore<C>` is that somewhere:
a small contract in `agenor-core` over a set of `KnowledgeDocument<C>`, with `search(query, topK)`
and a category axis for partitioning by intent, topic or tenant.

The contract says nothing about *how* a document is found. That is the point — a keyword store, a
TF-IDF store and a vector store are all substitutable behind it, and moving from one to another
does not change the agent that calls it.

## The smallest thing that works

`InMemoryKnowledgeStore` matches on keywords, needs no configuration and no network:

```java
KnowledgeStore<String> store = new InMemoryKnowledgeStore<>();
store.add(new KnowledgeDocument<>("faq-001", "Return policy",
        "Items can be returned within 30 days.", "returns",
        Set.of("return", "refund", "policy")));

List<KnowledgeDocument<String>> hits = store.search("how do I get a refund", 3);
```

This finds the document above, because "refund" is one of its keywords. It does not find it for
"money back", because nothing connects those words. That is the limit worth knowing before
reaching for anything larger.

## Embeddings: when keywords are not enough

An `EmbeddingProvider` turns text into a dense vector, so "money back" and "refund" land near
each other whether or not they share a word. `EmbeddingProviderFactory` in `agenor-adapters` is
the entry point:

```java
// Local, free, no signup - requires `ollama pull nomic-embed-text`
EmbeddingProvider embeddings = EmbeddingProviderFactory.ollama();

// Or a cloud model
EmbeddingProvider embeddings = EmbeddingProviderFactory.openAI(System.getenv("OPENAI_API_KEY"));

float[] vector = embeddings.embed("how do I get a refund").join();
```

Three things the interface gives you, each because a caller needs it:

| Call | What it is for |
|---|---|
| `embed(String)` | one vector, asynchronously |
| `embedAll(List<String>)` | one batched request for a whole corpus, not one call per document |
| `dimensions()` | the width to size a vector store with, known without a round trip |
| `modelId()` | which model produced the vectors, so a store can refuse to mix two of them |

Vectors from different models are not comparable. `dimensions()` and `modelId()` exist so that
this is checkable rather than a runtime surprise.

## Failures you can act on

Every failure arrives as an `EmbeddingException` inside the returned future, classified by
`ErrorType` so a caller can branch without parsing a message:

```java
embeddings.embed(text).exceptionally(ex -> {
    if (ex.getCause() instanceof EmbeddingException e) {
        switch (e.getErrorType()) {
            case RATE_LIMIT      -> scheduleRetryWithBackoff();
            case MODEL_NOT_FOUND -> log.error("run: ollama pull {}", embeddings.modelId());
            case NETWORK         -> fallBackToKeywordSearch();
            default              -> giveUp(e);
        }
    }
    return null;
});
```

`AUTHENTICATION`, `RATE_LIMIT`, `MODEL_NOT_FOUND`, `INVALID_INPUT`, `NETWORK`, `SERVER_ERROR`
and `UNKNOWN` are the cases. The distinction that matters most in practice is the one between
`NETWORK` and `MODEL_NOT_FOUND`: a daemon that is down and a model that was never pulled need
opposite responses, and both look like a failed HTTP call from the outside.

## Putting it together

`HybridKnowledgeStore` in `agenor-examples` is a `KnowledgeStore` that scores each document by a
weighted sum of TF-IDF and cosine similarity over embeddings — lexical matching for exact terms
and identifiers, vectors for everything phrased differently. It is worth reading as the whole
path: documents in, one batched `embedAll` at index time, one `embed` per query, and a plain
`search` for the agent that calls it.

It also shows the fallback that makes an example runnable with nothing installed: if the
embedding backend does not answer, the store logs it, stays on TF-IDF, and reports
`isEmbeddingsEnabled() == false` rather than pretending the answers came from vectors.

```bash
# TF-IDF plus embeddings, against local Ollama (the default)
mvn exec:java -pl agenor-examples \
  -Dexec.mainClass="dev.agenor.examples.support.SupportChatbotExample"

# TF-IDF only
EMBEDDING_BACKEND=none mvn exec:java -pl agenor-examples \
  -Dexec.mainClass="dev.agenor.examples.support.SupportChatbotExample"
```

## What this is not

There is no vector database adapter. `InMemoryVectorStore` in the examples is a `HashMap` and a
cosine loop — enough for a few thousand documents held in one JVM, and deliberately not presented
as more. A persistent or sharded store is a `KnowledgeStore` implementation someone writes against
the same contract; the interface is the point at which that swap costs nothing.

---

## Related

- ADR-011 — knowledge store core, and why the embedding half lives in `agenor-adapters`
- [LLM Overview](llm-integration.md) — the chat side of the same adapter module
