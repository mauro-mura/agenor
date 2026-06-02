package dev.agenor.core.llm;

import dev.agenor.core.exceptions.JenticException;

/**
 * Exception thrown during LLM operations.
 *
 * <p>This exception is used for all LLM-related errors, including:
 * <ul>
 *   <li>Invalid requests or parameters</li>
 *   <li>API errors (rate limits, authentication, etc.)</li>
 *   <li>Network failures</li>
 *   <li>Response parsing errors</li>
 *   <li>Model not available</li>
 * </ul>
 *
 * <p>Example usage:
 * <pre>{@code
 * try {
 *     LLMResponse response = provider.chat(request).get();
 * } catch (ExecutionException e) {
 *     if (e.getCause() instanceof LLMException llmEx) {
 *         if (llmEx.isRateLimit()) {
 *             // Handle rate limit
 *         } else if (llmEx.isAuthenticationError()) {
 *             // Handle auth error
 *         }
 *     }
 * }
 * }</pre>
 *
 * @since 0.3.0
 */
public class LLMException extends JenticException {

    /**
     * Type of LLM error.
     */
    public enum ErrorType {
        /**
         * Invalid request or parameters
         */
        INVALID_REQUEST,

        /**
         * Authentication failed (invalid API key)
         */
        AUTHENTICATION,

        /**
         * Rate limit exceeded
         */
        RATE_LIMIT,

        /**
         * Insufficient quota or credits
         */
        QUOTA_EXCEEDED,

        /**
         * Model not found or not available
         */
        MODEL_NOT_FOUND,

        /**
         * Network or connection error
         */
        NETWORK,

        /**
         * Error parsing response
         */
        PARSE_ERROR,

        /**
         * Server error (5xx)
         */
        SERVER_ERROR,

        /**
         * Context length exceeded
         */
        CONTEXT_LENGTH_EXCEEDED,

        /**
         * Content filtered by safety systems
         */
        CONTENT_FILTERED,

        /**
         * Feature not supported by provider
         */
        UNSUPPORTED_OPERATION,

        /**
         * Unknown or unclassified error
         */
        UNKNOWN
    }

    private final ErrorType errorType;
    private final String provider;
    private final String model;
    private final Integer statusCode;
    private final boolean retryable;

    /**
     * Create an LLM exception.
     *
     * @param message the error message
     */
    public LLMException(String message) {
        this(message, null, ErrorType.UNKNOWN, null, null, null, false);
    }

    /**
     * Create an LLM exception with a cause.
     *
     * @param message the error message
     * @param cause the underlying cause
     */
    public LLMException(String message, Throwable cause) {
        this(message, cause, ErrorType.UNKNOWN, null, null, null, false);
    }

    /**
     * Create an LLM exception with full details.
     *
     * @param message the error message
     * @param cause the underlying cause
     * @param errorType the type of error
     * @param provider the provider name
     * @param model the model name
     * @param statusCode HTTP status code (if applicable)
     * @param retryable whether the operation can be retried
     */
    public LLMException(String message, Throwable cause, ErrorType errorType,
                       String provider, String model, Integer statusCode, boolean retryable) {
        super(message, cause);
        this.errorType = errorType != null ? errorType : ErrorType.UNKNOWN;
        this.provider = provider;
        this.model = model;
        this.statusCode = statusCode;
        this.retryable = retryable;
    }

    // ========================================================================
    // Factory Methods
    // ========================================================================

    /**
     * Create an invalid request exception.
     */
    public static LLMException invalidRequest(String message) {
        return new LLMException(message, null, ErrorType.INVALID_REQUEST,
                              null, null, 400, false);
    }

    /**
     * Create an authentication error.
     */
    public static LLMException authenticationError(String provider, String message) {
        return new LLMException(message, null, ErrorType.AUTHENTICATION,
                              provider, null, 401, false);
    }

    /**
     * Create a rate limit error.
     */
    public static LLMException rateLimit(String provider, String message) {
        return new LLMException(message, null, ErrorType.RATE_LIMIT,
                              provider, null, 429, true);
    }

    /**
     * Create a quota exceeded error.
     */
    public static LLMException quotaExceeded(String provider, String message) {
        return new LLMException(message, null, ErrorType.QUOTA_EXCEEDED,
                              provider, null, 429, false);
    }

    /**
     * Create a model not found error.
     */
    public static LLMException modelNotFound(String provider, String model) {
        String message = String.format("Model '%s' not found for provider '%s'", model, provider);
        return new LLMException(message, null, ErrorType.MODEL_NOT_FOUND,
                              provider, model, 404, false);
    }

    /**
     * Create a network error.
     */
    public static LLMException networkError(String provider, String message, Throwable cause) {
        return new LLMException(message, cause, ErrorType.NETWORK,
                              provider, null, null, true);
    }

    /**
     * Create a server error.
     */
    public static LLMException serverError(String provider, String message, int statusCode) {
        return new LLMException(message, null, ErrorType.SERVER_ERROR,
                              provider, null, statusCode, true);
    }

    /**
     * Create a context length exceeded error.
     */
    public static LLMException contextLengthExceeded(String provider, String model,
                                                     int tokens, int maxTokens) {
        String message = String.format(
            "Context length exceeded: %d tokens (max: %d) for model '%s'",
            tokens, maxTokens, model
        );
        return new LLMException(message, null, ErrorType.CONTEXT_LENGTH_EXCEEDED,
                              provider, model, 400, false);
    }

    /**
     * Create a content filtered error.
     */
    public static LLMException contentFiltered(String provider, String reason) {
        String message = "Content was filtered: " + reason;
        return new LLMException(message, null, ErrorType.CONTENT_FILTERED,
                              provider, null, null, false);
    }

    /**
     * Create an unsupported operation error.
     */
    public static LLMException unsupportedOperation(String provider, String operation) {
        String message = String.format("Operation '%s' not supported by provider '%s'",
                                      operation, provider);
        return new LLMException(message, null, ErrorType.UNSUPPORTED_OPERATION,
                              provider, null, null, false);
    }

    // ========================================================================
    // Getters
    // ========================================================================

    /**
     * Get the error type.
     */
    public ErrorType getErrorType() {
        return errorType;
    }

    /**
     * Get the provider name.
     */
    public String getProvider() {
        return provider;
    }

    /**
     * Get the model name.
     */
    public String getModel() {
        return model;
    }

    /**
     * Get the HTTP status code (if applicable).
     */
    public Integer getStatusCode() {
        return statusCode;
    }

    /**
     * Check if this error is retryable.
     */
    public boolean isRetryable() {
        return retryable;
    }

    // ========================================================================
    // Convenience Checks
    // ========================================================================

    /**
     * Check if this is an authentication error.
     */
    public boolean isAuthenticationError() {
        return errorType == ErrorType.AUTHENTICATION;
    }

    /**
     * Check if this is a rate limit error.
     */
    public boolean isRateLimit() {
        return errorType == ErrorType.RATE_LIMIT;
    }

    /**
     * Check if this is a quota exceeded error.
     */
    public boolean isQuotaExceeded() {
        return errorType == ErrorType.QUOTA_EXCEEDED;
    }

    /**
     * Check if this is a network error.
     */
    public boolean isNetworkError() {
        return errorType == ErrorType.NETWORK;
    }

    /**
     * Check if this is a server error.
     */
    public boolean isServerError() {
        return errorType == ErrorType.SERVER_ERROR;
    }

    /**
     * Check if this is a context length error.
     */
    public boolean isContextLengthExceeded() {
        return errorType == ErrorType.CONTEXT_LENGTH_EXCEEDED;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("LLMException{");
        sb.append("type=").append(errorType);
        if (provider != null) {
            sb.append(", provider='").append(provider).append('\'');
        }
        if (model != null) {
            sb.append(", model='").append(model).append('\'');
        }
        if (statusCode != null) {
            sb.append(", statusCode=").append(statusCode);
        }
        sb.append(", retryable=").append(retryable);
        sb.append(", message='").append(getMessage()).append('\'');
        sb.append('}');
        return sb.toString();
    }
}
