package dev.jentic.examples.behaviors;

import dev.jentic.adapters.llm.LLMProviderFactory;
import dev.jentic.core.annotations.JenticAgent;
import dev.jentic.core.llm.LLMMessage;
import dev.jentic.core.llm.LLMProvider;
import dev.jentic.core.llm.LLMRequest;
import dev.jentic.core.reflection.ReflectionConfig;
import dev.jentic.runtime.JenticRuntime;
import dev.jentic.runtime.agent.LLMAgent;
import dev.jentic.runtime.behavior.ReflectionBehavior;
import dev.jentic.runtime.memory.InMemoryStore;
import dev.jentic.runtime.reflection.DefaultReflectionStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Demonstrates the Generate → Critique → Revise reflection loop using the real
 * OpenAI provider.
 *
 * <p>A {@link ContentReviewAgent} uses GPT-4o-mini to:
 * <ol>
 *   <li>Generate an initial product description.</li>
 *   <li>Critique it via {@link DefaultReflectionStrategy} (second GPT call).</li>
 *   <li>Revise it if the critique score is below the threshold.</li>
 *   <li>Repeat up to {@code maxIterations} times, then emit the best result.</li>
 * </ol>
 *
 * <p>Requires:
 * <pre>
 * export OPENAI_API_KEY=sk-...
 * </pre>
 *
 * <p>Run with:
 * <pre>
 * mvn compile exec:java -pl jentic-examples \
 *   -Dexec.mainClass="dev.jentic.examples.ReflectionExample"
 * </pre>
 */
public class ReflectionExample {

    private static final Logger log = LoggerFactory.getLogger(ReflectionExample.class);

    public static void main(String[] args) throws Exception {
      String apiKey = System.getenv("OPENAI_API_KEY");
      if (apiKey == null || apiKey.isBlank()) {
          log.error("ERROR: OPENAI_API_KEY environment variable is not set.");
          System.exit(1);
      }

      log.info("=== ReflectionBehavior Example (OpenAI) ===");

      // Two separate provider instances: generation uses higher temperature for
      // creative output; critique uses lower temperature for consistent scoring.
      LLMProvider generationProvider = LLMProviderFactory.openai()
              .apiKey(apiKey)
              .modelName("gpt-4o-mini")
              .temperature(0.7)
              .maxTokens(300)
              .build();

      LLMProvider critiqueProvider = LLMProviderFactory.openai()
              .apiKey(apiKey)
              .modelName("gpt-4o-mini")
              .temperature(0.3)
              .maxTokens(500)
              .build();

        JenticRuntime runtime = JenticRuntime.builder()
                .memoryStore(new InMemoryStore())
                .build();

        CountDownLatch done = new CountDownLatch(1);
        ContentReviewAgent agent = new ContentReviewAgent(generationProvider, critiqueProvider, done);
        runtime.registerAgent(agent);
        agent.startReflectionBehavior();

        runtime.start().join();

        if (!done.await(120, TimeUnit.SECONDS)) {
            log.error("Timed out waiting for reflection loop.");
        }

        runtime.stop().join();
        log.info("=== Example complete ===");
    }

    // -------------------------------------------------------------------------
    // Agent
    // -------------------------------------------------------------------------

    @JenticAgent("content-review-agent")
    public static class ContentReviewAgent extends LLMAgent {

        private static final String TASK =
                "Write a compelling 2-sentence product description for a smart home thermostat " +
                "targeting eco-conscious homeowners. Include a specific energy-saving claim.";

        private static final String GENERATION_SYSTEM =
                "You are an expert copywriter specialising in smart home technology. " +
                "Write clear, benefit-driven product descriptions. " +
                "Output ONLY the description — no preamble, no meta-commentary.";

        private static final String REVISION_SYSTEM =
                "You are an expert copywriter. Improve the product description based on the " +
                "critique provided. Output ONLY the revised description, nothing else.";

        private final LLMProvider generationProvider;
        private final LLMProvider critiqueProvider;
        private final CountDownLatch latch;
        private int iterationNumber = 0;

        public ContentReviewAgent(
                LLMProvider generationProvider,
                LLMProvider critiqueProvider,
                CountDownLatch latch) {
            super("content-review-agent");
            this.generationProvider = generationProvider;
            this.critiqueProvider = critiqueProvider;
            this.latch = latch;
            // Register critique provider so reflect() works without explicit strategy
            setLLMProvider(critiqueProvider);
        }

        /** Creates and registers the ReflectionBehavior on this agent. */
        public void startReflectionBehavior() {
            ReflectionConfig config = new ReflectionConfig(
                    3,      // up to 3 revision cycles
                    0.85,   // early-stop when score >= 0.85
                    null    // use DefaultReflectionStrategy's built-in critique prompt
            );

            ReflectionBehavior behavior = ReflectionBehavior.builder("content-reflection")
                    .task(TASK)
                    .action(this::generateDescription)
                    .revise(this::reviseDescription)
                    .strategy(new DefaultReflectionStrategy(critiqueProvider))
                    .config(config)
                    .onResult(this::onFinalResult)
                    .build();

            addBehavior(behavior);
        }

        // ---- generation ----

        private String generateDescription() {
            iterationNumber++;
            log.info("─── Iteration {} — generating initial description ──────────",
                    iterationNumber);

            String output = generationProvider.chat(
                    LLMRequest.builder()
                            .addMessage(LLMMessage.system(GENERATION_SYSTEM))
                            .addMessage(LLMMessage.user("Task: " + TASK))
                            .build())
                    .join().content();

            log.info("Generated: {}", output);
            return output;
        }

        // ---- revision ----

        private String reviseDescription(String previous, String feedback) {
            iterationNumber++;
            log.info("─── Iteration {} — revising ────────────────────────────────",
                    iterationNumber);

            // Print only the first 3 lines of feedback to keep the console readable
            String feedbackSummary = feedback.lines()
                    .limit(3)
                    .reduce("", (a, b) -> a.isBlank() ? b : a + " | " + b);
            log.info("Feedback: {}", feedbackSummary);

            String revised = generationProvider.chat(
                    LLMRequest.builder()
                            .addMessage(LLMMessage.system(REVISION_SYSTEM))
                            .addMessage(LLMMessage.user(
                                    "Task: " + TASK + "\n\n" +
                                    "Previous version:\n" + previous + "\n\n" +
                                    "Critique:\n" + feedback))
                            .build())
                    .join().content();

            log.info("Revised: {}", revised);
            return revised;
        }

        // ---- result ----

        private void onFinalResult(String result) {
            log.info("✅ Final output after {} iteration(s): {}", iterationNumber, result);
            latch.countDown();
        }
    }
}