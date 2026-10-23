package dev.agenor.adapters.llm;

import org.junit.jupiter.api.DisplayName;

import dev.agenor.core.llm.LLMProvider;
import dev.agenor.runtime.llm.ScriptedLLMProvider;

/**
 * Runs {@link LLMProviderContractTest} against {@link ScriptedLLMProvider}, which ships in
 * agenor-runtime-llm as the provider for tests and offline examples.
 *
 * <p>A stand-in that does not honour the contract makes tests pass that a real provider would
 * fail: one that ignored the model named on the request would hide exactly the ADR-017 defect
 * this suite was written for. {@code StubLLMProviderContractTest} stays: it is the check that the
 * clauses do not encode LangChain4j, and it must not depend on a type it is checking.
 */
@DisplayName("ScriptedLLMProvider honours the LLMProvider contract")
class ScriptedLLMProviderContractTest extends LLMProviderContractTest {

    private final ScriptedLLMProvider scripted = ScriptedLLMProvider.create();

    @Override
    protected LLMProvider provider() {
        return scripted;
    }

    @Override
    protected String builtWithModel() {
        return ScriptedLLMProvider.MODEL;
    }

    @Override
    protected void givenChatAnswers(String text) {
        scripted.reply(text);
    }

    @Override
    protected void givenStreamEmits(String... partials) {
        scripted.reply(String.join("", partials));
    }
}
