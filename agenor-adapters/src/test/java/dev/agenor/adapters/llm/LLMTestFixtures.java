package dev.agenor.adapters.llm;

import java.lang.reflect.Field;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.ChatResponseMetadata;
import dev.langchain4j.model.output.FinishReason;

/**
 * Shared fixtures for the LLM adapter tests.
 *
 * <p>The providers keep their LangChain4j clients in private final fields with no setter, so a
 * test stands one up and replaces the field. That is the convention already in use across this
 * package's tests; it is gathered here rather than written out once per test class.
 */
final class LLMTestFixtures {

    private LLMTestFixtures() {}

    /** Replaces a private field on a provider with a stand-in for its client. */
    static void inject(Object target, String field, Object value) {
        try {
            Field f = target.getClass().getDeclaredField(field);
            f.setAccessible(true);
            f.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("could not inject " + field + " into " + target, e);
        }
    }

    /**
     * A complete response carrying {@code text}.
     *
     * <p>The id matters: OpenAI and Anthropic pass {@code ChatResponse.id()} straight into
     * {@code LLMResponse.builder}, which rejects null, so a response built without metadata fails
     * with a {@code NullPointerException} from inside the builder rather than anything readable.
     */
    static ChatResponse chatResponse(String text) {
        return ChatResponse.builder()
                .aiMessage(AiMessage.from(text))
                .metadata(ChatResponseMetadata.builder()
                        .id("resp-1")
                        .finishReason(FinishReason.STOP)
                        .build())
                .build();
    }
}
