package dev.agenor.core.llm;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LLMExceptionTest {

    @Test
    void shouldCreateSimpleException() {
        // Given
        String message = "Test error";

        // When
        var exception = new LLMException(message);

        // Then
        assertEquals(message, exception.getMessage());
        assertEquals(LLMException.ErrorType.UNKNOWN, exception.getErrorType());
        assertNull(exception.getProvider());
        assertNull(exception.getModel());
        assertNull(exception.getStatusCode());
        assertFalse(exception.isRetryable());
    }

    @Test
    void shouldCreateExceptionWithCause() {
        // Given
        String message = "Test error";
        var cause = new RuntimeException("Root cause");

        // When
        var exception = new LLMException(message, cause);

        // Then
        assertEquals(message, exception.getMessage());
        assertEquals(cause, exception.getCause());
        assertEquals(LLMException.ErrorType.UNKNOWN, exception.getErrorType());
    }

    @Test
    void shouldCreateExceptionWithFullDetails() {
        // Given
        String message = "API error";
        var cause = new RuntimeException("Network issue");
        var errorType = LLMException.ErrorType.NETWORK;
        String provider = "openai";
        String model = "gpt-4";
        Integer statusCode = 500;
        boolean retryable = true;

        // When
        var exception = new LLMException(message, cause, errorType,
                                        provider, model, statusCode, retryable);

        // Then
        assertEquals(message, exception.getMessage());
        assertEquals(cause, exception.getCause());
        assertEquals(errorType, exception.getErrorType());
        assertEquals(provider, exception.getProvider());
        assertEquals(model, exception.getModel());
        assertEquals(statusCode, exception.getStatusCode());
        assertTrue(exception.isRetryable());
    }

    @Test
    void shouldCreateInvalidRequestException() {
        // Given
        String message = "Invalid parameters";

        // When
        var exception = LLMException.invalidRequest(message);

        // Then
        assertEquals(message, exception.getMessage());
        assertEquals(LLMException.ErrorType.INVALID_REQUEST, exception.getErrorType());
        assertEquals(400, exception.getStatusCode());
        assertFalse(exception.isRetryable());
    }

    @Test
    void shouldCreateAuthenticationError() {
        // Given
        String provider = "anthropic";
        String message = "Invalid API key";

        // When
        var exception = LLMException.authenticationError(provider, message);

        // Then
        assertEquals(message, exception.getMessage());
        assertEquals(LLMException.ErrorType.AUTHENTICATION, exception.getErrorType());
        assertEquals(provider, exception.getProvider());
        assertEquals(401, exception.getStatusCode());
        assertFalse(exception.isRetryable());
        assertTrue(exception.isAuthenticationError());
    }

    @Test
    void shouldCreateRateLimitError() {
        // Given
        String provider = "openai";
        String message = "Rate limit exceeded";

        // When
        var exception = LLMException.rateLimit(provider, message);

        // Then
        assertEquals(message, exception.getMessage());
        assertEquals(LLMException.ErrorType.RATE_LIMIT, exception.getErrorType());
        assertEquals(provider, exception.getProvider());
        assertEquals(429, exception.getStatusCode());
        assertTrue(exception.isRetryable());
        assertTrue(exception.isRateLimit());
    }

    @Test
    void shouldCreateQuotaExceededError() {
        // Given
        String provider = "openai";
        String message = "Quota exceeded";

        // When
        var exception = LLMException.quotaExceeded(provider, message);

        // Then
        assertEquals(message, exception.getMessage());
        assertEquals(LLMException.ErrorType.QUOTA_EXCEEDED, exception.getErrorType());
        assertEquals(provider, exception.getProvider());
        assertEquals(429, exception.getStatusCode());
        assertFalse(exception.isRetryable());
        assertTrue(exception.isQuotaExceeded());
    }

    @Test
    void shouldCreateModelNotFoundError() {
        // Given
        String provider = "openai";
        String model = "gpt-5";

        // When
        var exception = LLMException.modelNotFound(provider, model);

        // Then
        assertTrue(exception.getMessage().contains(model));
        assertTrue(exception.getMessage().contains(provider));
        assertEquals(LLMException.ErrorType.MODEL_NOT_FOUND, exception.getErrorType());
        assertEquals(provider, exception.getProvider());
        assertEquals(model, exception.getModel());
        assertEquals(404, exception.getStatusCode());
        assertFalse(exception.isRetryable());
    }

    @Test
    void shouldCreateNetworkError() {
        // Given
        String provider = "anthropic";
        String message = "Connection timeout";
        var cause = new java.io.IOException("Network error");

        // When
        var exception = LLMException.networkError(provider, message, cause);

        // Then
        assertEquals(message, exception.getMessage());
        assertEquals(cause, exception.getCause());
        assertEquals(LLMException.ErrorType.NETWORK, exception.getErrorType());
        assertEquals(provider, exception.getProvider());
        assertTrue(exception.isRetryable());
        assertTrue(exception.isNetworkError());
    }

    @Test
    void shouldCreateServerError() {
        // Given
        String provider = "openai";
        String message = "Internal server error";
        int statusCode = 503;

        // When
        var exception = LLMException.serverError(provider, message, statusCode);

        // Then
        assertEquals(message, exception.getMessage());
        assertEquals(LLMException.ErrorType.SERVER_ERROR, exception.getErrorType());
        assertEquals(provider, exception.getProvider());
        assertEquals(statusCode, exception.getStatusCode());
        assertTrue(exception.isRetryable());
        assertTrue(exception.isServerError());
    }

    @Test
    void shouldCreateContextLengthExceededError() {
        // Given
        String provider = "openai";
        String model = "gpt-4";
        int tokens = 10000;
        int maxTokens = 8192;

        // When
        var exception = LLMException.contextLengthExceeded(provider, model, tokens, maxTokens);

        // Then
        assertTrue(exception.getMessage().contains(String.valueOf(tokens)));
        assertTrue(exception.getMessage().contains(String.valueOf(maxTokens)));
        assertTrue(exception.getMessage().contains(model));
        assertEquals(LLMException.ErrorType.CONTEXT_LENGTH_EXCEEDED, exception.getErrorType());
        assertEquals(provider, exception.getProvider());
        assertEquals(model, exception.getModel());
        assertEquals(400, exception.getStatusCode());
        assertFalse(exception.isRetryable());
        assertTrue(exception.isContextLengthExceeded());
    }

    @Test
    void shouldCreateContentFilteredError() {
        // Given
        String provider = "anthropic";
        String reason = "Unsafe content detected";

        // When
        var exception = LLMException.contentFiltered(provider, reason);

        // Then
        assertTrue(exception.getMessage().contains(reason));
        assertEquals(LLMException.ErrorType.CONTENT_FILTERED, exception.getErrorType());
        assertEquals(provider, exception.getProvider());
        assertFalse(exception.isRetryable());
    }

    @Test
    void shouldCreateUnsupportedOperationError() {
        // Given
        String provider = "test-provider";
        String operation = "streaming";

        // When
        var exception = LLMException.unsupportedOperation(provider, operation);

        // Then
        assertTrue(exception.getMessage().contains(operation));
        assertTrue(exception.getMessage().contains(provider));
        assertEquals(LLMException.ErrorType.UNSUPPORTED_OPERATION, exception.getErrorType());
        assertEquals(provider, exception.getProvider());
        assertFalse(exception.isRetryable());
    }

    @Test
    void shouldDefaultToUnknownErrorType() {
        // Given
        var exception = new LLMException("message", null, null, null, null, null, false);

        // Then
        assertEquals(LLMException.ErrorType.UNKNOWN, exception.getErrorType());
    }

    @Test
    void shouldGenerateToStringWithAllFields() {
        // Given
        var exception = new LLMException("Test error", null,
            LLMException.ErrorType.RATE_LIMIT, "openai", "gpt-4", 429, true);

        // When
        String str = exception.toString();

        // Then
        assertTrue(str.contains("RATE_LIMIT"));
        assertTrue(str.contains("openai"));
        assertTrue(str.contains("gpt-4"));
        assertTrue(str.contains("429"));
        assertTrue(str.contains("true"));
        assertTrue(str.contains("Test error"));
    }

    @Test
    void shouldGenerateToStringWithMinimalFields() {
        // Given
        var exception = new LLMException("Simple error");

        // When
        String str = exception.toString();

        // Then
        assertTrue(str.contains("UNKNOWN"));
        assertTrue(str.contains("Simple error"));
        assertFalse(str.contains("provider="));
        assertFalse(str.contains("model="));
    }

    @Test
    void shouldCheckAuthenticationError() {
        // Given
        var authError = LLMException.authenticationError("openai", "Invalid key");
        var otherError = LLMException.rateLimit("openai", "Rate limit");

        // Then
        assertTrue(authError.isAuthenticationError());
        assertFalse(otherError.isAuthenticationError());
    }

    @Test
    void shouldCheckAllErrorTypeCheckers() {
        // Given
        var authError = LLMException.authenticationError("provider", "msg");
        var rateLimit = LLMException.rateLimit("provider", "msg");
        var quotaError = LLMException.quotaExceeded("provider", "msg");
        var networkError = LLMException.networkError("provider", "msg", null);
        var serverError = LLMException.serverError("provider", "msg", 500);
        var contextError = LLMException.contextLengthExceeded("provider", "model", 100, 50);

        // Then
        assertTrue(authError.isAuthenticationError());
        assertTrue(rateLimit.isRateLimit());
        assertTrue(quotaError.isQuotaExceeded());
        assertTrue(networkError.isNetworkError());
        assertTrue(serverError.isServerError());
        assertTrue(contextError.isContextLengthExceeded());
    }

    @Test
    void shouldHandleNullProvider() {
        // Given/When
        var exception = new LLMException("error", null,
            LLMException.ErrorType.UNKNOWN, null, null, null, false);

        // Then
        assertNull(exception.getProvider());
        assertDoesNotThrow(() -> exception.toString());
    }

    @Test
    void shouldHandleNullModel() {
        // Given/When
        var exception = new LLMException("error", null,
            LLMException.ErrorType.UNKNOWN, "provider", null, null, false);

        // Then
        assertNull(exception.getModel());
        assertDoesNotThrow(() -> exception.toString());
    }

    @Test
    void shouldHandleNullStatusCode() {
        // Given/When
        var exception = new LLMException("error", null,
            LLMException.ErrorType.NETWORK, "provider", null, null, true);

        // Then
        assertNull(exception.getStatusCode());
        assertDoesNotThrow(() -> exception.toString());
    }
}
