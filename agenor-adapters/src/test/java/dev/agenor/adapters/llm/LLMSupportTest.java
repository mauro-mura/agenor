package dev.agenor.adapters.llm;

import dev.agenor.core.llm.LLMException;
import dev.agenor.core.llm.LLMException.ErrorType;
import dev.langchain4j.exception.AuthenticationException;
import dev.langchain4j.exception.ContentFilteredException;
import dev.langchain4j.exception.HttpException;
import dev.langchain4j.exception.InternalServerException;
import dev.langchain4j.exception.InvalidRequestException;
import dev.langchain4j.exception.ModelNotFoundException;
import dev.langchain4j.exception.RateLimitException;
import dev.langchain4j.exception.TimeoutException;
import dev.langchain4j.exception.UnresolvedModelServerException;
import dev.langchain4j.exception.UnsupportedFeatureException;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link LLMSupport}'s classification of a provider failure into an
 * {@link LLMException}, mirroring {@code EmbeddingSupport}'s own classifier tests.
 *
 * <p>This is unit-level on purpose: a contract test pinned to a real or mocked
 * {@code LLMProvider} is the wrong altitude for a branch-by-branch classifier, the lesson
 * {@code LLMProviderContractTest}'s own history already records for the embedding side.
 */
class LLMSupportTest {

    private static final String PROVIDER = "TestProvider";
    private static final String MODEL = "test-model";

    // ========== CLASSIFICATION, ONE PER LANGCHAIN4J EXCEPTION TYPE ==========

    @Test
    void wrap_shouldClassifyAuthenticationException() {
        var wrapped = LLMSupport.wrap(PROVIDER, MODEL, new AuthenticationException("bad key"));
        assertThat(wrapped.getErrorType()).isEqualTo(ErrorType.AUTHENTICATION);
    }

    @Test
    void wrap_shouldClassifyRateLimitException() {
        var wrapped = LLMSupport.wrap(PROVIDER, MODEL, new RateLimitException("slow down"));
        assertThat(wrapped.getErrorType()).isEqualTo(ErrorType.RATE_LIMIT);
    }

    @Test
    void wrap_shouldClassifyModelNotFoundException() {
        var wrapped = LLMSupport.wrap(PROVIDER, MODEL, new ModelNotFoundException("no such model"));
        assertThat(wrapped.getErrorType()).isEqualTo(ErrorType.MODEL_NOT_FOUND);
    }

    @Test
    void wrap_shouldClassifyUnsupportedFeatureException() {
        var wrapped = LLMSupport.wrap(PROVIDER, MODEL, new UnsupportedFeatureException("no tools"));
        assertThat(wrapped.getErrorType()).isEqualTo(ErrorType.UNSUPPORTED_OPERATION);
    }

    @Test
    void wrap_shouldClassifyInvalidRequestException() {
        var wrapped = LLMSupport.wrap(PROVIDER, MODEL, new InvalidRequestException("bad request"));
        assertThat(wrapped.getErrorType()).isEqualTo(ErrorType.INVALID_REQUEST);
    }

    @Test
    void wrap_shouldClassifyContentFilteredExceptionBeforeItsInvalidRequestSuperclass() {
        // ContentFilteredException extends InvalidRequestException - the more specific check
        // must win, or every filtered response would misreport as a plain bad request.
        var wrapped = LLMSupport.wrap(PROVIDER, MODEL, new ContentFilteredException("blocked"));
        assertThat(wrapped.getErrorType()).isEqualTo(ErrorType.CONTENT_FILTERED);
    }

    @Test
    void wrap_shouldClassifyTimeoutExceptionAsNetwork() {
        var wrapped = LLMSupport.wrap(PROVIDER, MODEL, new TimeoutException("timed out"));
        assertThat(wrapped.getErrorType()).isEqualTo(ErrorType.NETWORK);
    }

    @Test
    void wrap_shouldClassifyUnresolvedModelServerExceptionAsNetwork() {
        var wrapped = LLMSupport.wrap(PROVIDER, MODEL,
                new UnresolvedModelServerException("cannot reach host"));
        assertThat(wrapped.getErrorType()).isEqualTo(ErrorType.NETWORK);
    }

    @Test
    void wrap_shouldClassifyPlainIOExceptionAsNetwork() {
        var wrapped = LLMSupport.wrap(PROVIDER, MODEL, new IOException("connection reset"));
        assertThat(wrapped.getErrorType()).isEqualTo(ErrorType.NETWORK);
    }

    @Test
    void wrap_shouldClassifyInternalServerExceptionAsServerError() {
        var wrapped = LLMSupport.wrap(PROVIDER, MODEL, new InternalServerException("500"));
        assertThat(wrapped.getErrorType()).isEqualTo(ErrorType.SERVER_ERROR);
    }

    @Test
    void wrap_shouldClassifyUnrecognisedExceptionAsUnknown() {
        var wrapped = LLMSupport.wrap(PROVIDER, MODEL, new RuntimeException("who knows"));
        assertThat(wrapped.getErrorType()).isEqualTo(ErrorType.UNKNOWN);
    }

    // ========== HttpException: CLASSIFIED AND RETRIED BY STATUS CODE ==========

    @Test
    void wrap_shouldClassifyHttpException401AsAuthentication() {
        var wrapped = LLMSupport.wrap(PROVIDER, MODEL, new HttpException(401, "unauthorized"));
        assertThat(wrapped.getErrorType()).isEqualTo(ErrorType.AUTHENTICATION);
        assertThat(wrapped.getStatusCode()).isEqualTo(401);
        assertThat(wrapped.isRetryable()).isFalse();
    }

    @Test
    void wrap_shouldClassifyHttpException403AsAuthentication() {
        var wrapped = LLMSupport.wrap(PROVIDER, MODEL, new HttpException(403, "forbidden"));
        assertThat(wrapped.getErrorType()).isEqualTo(ErrorType.AUTHENTICATION);
    }

    @Test
    void wrap_shouldClassifyHttpException404AsModelNotFound() {
        var wrapped = LLMSupport.wrap(PROVIDER, MODEL, new HttpException(404, "not found"));
        assertThat(wrapped.getErrorType()).isEqualTo(ErrorType.MODEL_NOT_FOUND);
    }

    @Test
    void wrap_shouldClassifyHttpException429AsRateLimitAndRetryable() {
        var wrapped = LLMSupport.wrap(PROVIDER, MODEL, new HttpException(429, "too many requests"));
        assertThat(wrapped.getErrorType()).isEqualTo(ErrorType.RATE_LIMIT);
        assertThat(wrapped.isRetryable()).isTrue();
    }

    @Test
    void wrap_shouldClassifyHttpException500AsServerErrorAndRetryable() {
        var wrapped = LLMSupport.wrap(PROVIDER, MODEL, new HttpException(500, "boom"));
        assertThat(wrapped.getErrorType()).isEqualTo(ErrorType.SERVER_ERROR);
        assertThat(wrapped.isRetryable()).isTrue();
    }

    @Test
    void wrap_shouldClassifyHttpException503AsServerErrorAndRetryable() {
        var wrapped = LLMSupport.wrap(PROVIDER, MODEL, new HttpException(503, "unavailable"));
        assertThat(wrapped.getErrorType()).isEqualTo(ErrorType.SERVER_ERROR);
        assertThat(wrapped.isRetryable()).isTrue();
    }

    @Test
    void wrap_shouldClassifyHttpException400AsInvalidRequestAndNotRetryable() {
        var wrapped = LLMSupport.wrap(PROVIDER, MODEL, new HttpException(400, "bad"));
        assertThat(wrapped.getErrorType()).isEqualTo(ErrorType.INVALID_REQUEST);
        assertThat(wrapped.isRetryable()).isFalse();
    }

    @Test
    void wrap_shouldClassifyHttpExceptionWithUnrecognisedStatusAsUnknown() {
        var wrapped = LLMSupport.wrap(PROVIDER, MODEL, new HttpException(200, "unexpected"));
        assertThat(wrapped.getErrorType()).isEqualTo(ErrorType.UNKNOWN);
    }

    // ========== RETRYABLE, INDEPENDENT OF ERRORTYPE ==========

    @Test
    void wrap_shouldMarkRateLimitAsRetryable() {
        assertThat(LLMSupport.wrap(PROVIDER, MODEL, new RateLimitException("slow down"))
                .isRetryable()).isTrue();
    }

    @Test
    void wrap_shouldMarkAuthenticationAsNotRetryable() {
        assertThat(LLMSupport.wrap(PROVIDER, MODEL, new AuthenticationException("bad key"))
                .isRetryable()).isFalse();
    }

    @Test
    void wrap_shouldMarkInternalServerExceptionAsRetryable() {
        // InternalServerException extends RetriableException.
        assertThat(LLMSupport.wrap(PROVIDER, MODEL, new InternalServerException("500"))
                .isRetryable()).isTrue();
    }

    @Test
    void wrap_shouldMarkUnrecognisedExceptionAsNotRetryable() {
        assertThat(LLMSupport.wrap(PROVIDER, MODEL, new RuntimeException("who knows"))
                .isRetryable()).isFalse();
    }

    // ========== CAUSE-CHAIN WALKING ==========

    @Test
    void wrap_shouldClassifyByWalkingTheWholeCauseChain() {
        // LangChain4j's Ollama model reports an unreachable daemon this way: the outer frame
        // classifies as UNKNOWN, and only the cause carries the real answer.
        var wrapped = LLMSupport.wrap(PROVIDER, MODEL,
                new RuntimeException(new java.net.ConnectException("refused")));
        assertThat(wrapped.getErrorType()).isEqualTo(ErrorType.NETWORK);
    }

    @Test
    void wrap_shouldStopAtASelfReferentialCauseInsteadOfLooping() {
        // Throwable.initCause() refuses direct self-causation, so a self-referential chain
        // is built the only way one can arise: a Throwable overriding getCause() itself.
        class SelfReferential extends RuntimeException {
            @Override
            public synchronized Throwable getCause() {
                return this;
            }
        }
        var wrapped = LLMSupport.wrap(PROVIDER, MODEL, new SelfReferential());
        assertThat(wrapped.getErrorType()).isEqualTo(ErrorType.UNKNOWN);
    }

    // ========== UNWRAPPING COMPLETION/EXECUTION LAYERS ==========

    @Test
    void wrap_shouldUnwrapCompletionExceptionBeforeClassifying() {
        var wrapped = LLMSupport.wrap(PROVIDER, MODEL,
                new CompletionException(new AuthenticationException("bad key")));
        assertThat(wrapped.getErrorType()).isEqualTo(ErrorType.AUTHENTICATION);
    }

    @Test
    void wrap_shouldUnwrapExecutionExceptionBeforeClassifying() throws Exception {
        var wrapped = LLMSupport.wrap(PROVIDER, MODEL,
                new ExecutionException(new RateLimitException("slow down")));
        assertThat(wrapped.getErrorType()).isEqualTo(ErrorType.RATE_LIMIT);
    }

    @Test
    void wrap_shouldNotWrapAnAlreadyClassifiedLLMExceptionAgain() {
        LLMException original = LLMException.rateLimit(PROVIDER, "already classified");
        var wrapped = LLMSupport.wrap(PROVIDER, MODEL, original);
        assertThat(wrapped).isSameAs(original);
    }

    @Test
    void wrap_shouldUnwrapToAnAlreadyClassifiedLLMException() {
        LLMException original = LLMException.rateLimit(PROVIDER, "already classified");
        var wrapped = LLMSupport.wrap(PROVIDER, MODEL, new CompletionException(original));
        assertThat(wrapped).isSameAs(original);
    }

    // ========== PROVIDER AND MODEL ATTACHED ==========

    @Test
    void wrap_shouldAttachProviderAndModel() {
        var wrapped = LLMSupport.wrap(PROVIDER, MODEL, new RuntimeException("boom"));
        assertThat(wrapped.getProvider()).isEqualTo(PROVIDER);
        assertThat(wrapped.getModel()).isEqualTo(MODEL);
    }

    @Test
    void wrap_shouldAcceptANullModelForAFailureBeforeOneWasResolved() {
        var wrapped = LLMSupport.wrap(PROVIDER, null, new RuntimeException("boom"));
        assertThat(wrapped.getModel()).isNull();
    }

    @Test
    void wrap_shouldIncludeTheCauseMessageInItsOwnMessage() {
        var wrapped = LLMSupport.wrap(PROVIDER, MODEL, new RuntimeException("the real reason"));
        assertThat(wrapped.getMessage()).contains("the real reason");
        assertThat(wrapped.getCause()).isInstanceOf(RuntimeException.class);
    }
}
