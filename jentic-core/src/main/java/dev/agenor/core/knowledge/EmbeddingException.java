package dev.agenor.core.knowledge;

import dev.agenor.core.exceptions.JenticException;

import java.io.Serial;

/**
 * Typed exception thrown by {@link EmbeddingProvider} implementations.
 *
 * <p>Mirrors the structure of {@code LLMException}. The {@link ErrorType} enum
 * allows callers to apply provider-independent error-handling strategies
 * (e.g. exponential back-off on {@link ErrorType#RATE_LIMIT}).
 *
 * <p>Example:
 * <pre>{@code
 * provider.embed(text).exceptionally(ex -> {
 *     if (ex.getCause() instanceof EmbeddingException e) {
 *         if (e.getErrorType() == EmbeddingException.ErrorType.RATE_LIMIT) {
 *             // schedule retry with back-off
 *         }
 *     }
 *     return null;
 * });
 * }</pre>
 */
public class EmbeddingException extends JenticException {

	@Serial
	private static final long serialVersionUID = -1951833008129004485L;

	/**
     * Classifies the root cause of an embedding failure.
     */
    public enum ErrorType {
        /** Invalid or missing API key. */
        AUTHENTICATION,
        /** Too many requests in a short period. */
        RATE_LIMIT,
        /** Account quota or credit exhausted. */
        QUOTA_EXCEEDED,
        /** The input text is null, blank, or exceeds provider limits. */
        INVALID_INPUT,
        /** The requested embedding model does not exist or is not accessible. */
        MODEL_NOT_FOUND,
        /** Network-level failure (connection refused, timeout, etc.). */
        NETWORK,
        /** Provider-side error (5xx HTTP status). */
        SERVER_ERROR,
        /** Catch-all for unclassified errors. */
        UNKNOWN
    }

    private final ErrorType errorType;

    /**
     * Constructs a new {@code EmbeddingException} without a cause.
     *
     * @param message   human-readable error description
     * @param errorType classification of the error
     */
    public EmbeddingException(String message, ErrorType errorType) {
        super(message);
        this.errorType = errorType;
    }

    /**
     * Constructs a new {@code EmbeddingException} wrapping an underlying cause.
     *
     * @param message   human-readable error description
     * @param errorType classification of the error
     * @param cause     underlying exception
     */
    public EmbeddingException(String message, ErrorType errorType, Throwable cause) {
        super(message, cause);
        this.errorType = errorType;
    }

    /**
     * Returns the error classification that allows provider-independent handling.
     *
     * @return non-null {@link ErrorType}
     */
    public ErrorType getErrorType() {
        return errorType;
    }
}
