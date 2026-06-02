package dev.agenor.core.memory;

import dev.agenor.core.exceptions.AgenorException;

/**
 * Base exception for memory storage operations.
 *
 * <p>Thrown when memory operations fail due to:
 * <ul>
 *   <li>Storage backend errors (database, file system)</li>
 *   <li>Validation failures</li>
 *   <li>Quota or capacity limits exceeded</li>
 *   <li>Access permission violations</li>
 * </ul>
 *
 * <p>Example usage:
 * <pre>{@code
 * try {
 *     memoryStore.store(key, entry, scope).join();
 * } catch (MemoryException e) {
 *     if (e.isRetryable()) {
 *         // Retry the operation
 *     } else {
 *         // Handle fatal error
 *         log.error("Memory operation failed: {}", e.getMessage());
 *     }
 * }
 * }</pre>
 *
 * @since 0.6.0
 */
public class MemoryException extends AgenorException {

    private static final long serialVersionUID = -2380496751158852185L;
	private final ErrorType errorType;
    private final String storeName;
    private final boolean retryable;

    /**
     * Creates a new memory exception.
     *
     * @param message the error message
     */
    public MemoryException(String message) {
        this(message, null, ErrorType.UNKNOWN, null, false);
    }

    /**
     * Creates a new memory exception with a cause.
     *
     * @param message the error message
     * @param cause the underlying cause
     */
    public MemoryException(String message, Throwable cause) {
        this(message, cause, ErrorType.UNKNOWN, null, false);
    }

    /**
     * Creates a new memory exception with detailed information.
     *
     * @param message the error message
     * @param cause the underlying cause
     * @param errorType the type of error
     * @param storeName the name of the store where the error occurred
     * @param retryable whether the operation can be retried
     */
    public MemoryException(String message, Throwable cause, ErrorType errorType,
                          String storeName, boolean retryable) {
        super(formatMessage(message, storeName, errorType), cause);
        this.errorType = errorType != null ? errorType : ErrorType.UNKNOWN;
        this.storeName = storeName;
        this.retryable = retryable;
    }

    /**
     * Gets the type of error that occurred.
     *
     * @return the error type
     */
    public ErrorType getErrorType() {
        return errorType;
    }

    /**
     * Gets the name of the store where the error occurred.
     *
     * @return the store name, or null if not applicable
     */
    public String getStoreName() {
        return storeName;
    }

    /**
     * Checks if this operation can be retried.
     *
     * @return true if retryable, false otherwise
     */
    public boolean isRetryable() {
        return retryable;
    }

    private static String formatMessage(String message, String storeName, ErrorType errorType) {
        if (storeName != null) {
            return String.format("[%s:%s] %s", storeName, errorType, message);
        }
        return String.format("[%s] %s", errorType, message);
    }

    // Factory methods for common error scenarios

    /**
     * Creates a storage error (typically from database or file system).
     *
     * @param storeName the name of the store
     * @param message the error message
     * @param cause the underlying cause
     * @return a new MemoryException
     */
    public static MemoryException storageError(String storeName, String message, Throwable cause) {
        return new MemoryException(message, cause, ErrorType.STORAGE_ERROR, storeName, true);
    }

    /**
     * Creates a validation error for invalid input.
     *
     * @param message the error message
     * @return a new MemoryException
     */
    public static MemoryException validationError(String message) {
        return new MemoryException(message, null, ErrorType.VALIDATION_ERROR, null, false);
    }

    /**
     * Creates a quota exceeded error.
     *
     * @param storeName the name of the store
     * @param message the error message
     * @return a new MemoryException
     */
    public static MemoryException quotaExceeded(String storeName, String message) {
        return new MemoryException(message, null, ErrorType.QUOTA_EXCEEDED, storeName, false);
    }

    /**
     * Creates an access denied error.
     *
     * @param message the error message
     * @return a new MemoryException
     */
    public static MemoryException accessDenied(String message) {
        return new MemoryException(message, null, ErrorType.ACCESS_DENIED, null, false);
    }

    /**
     * Creates a not found error.
     *
     * @param key the memory key that was not found
     * @return a new MemoryException
     */
    public static MemoryException notFound(String key) {
        return new MemoryException("Memory entry not found: " + key, null,
                                  ErrorType.NOT_FOUND, null, false);
    }

    /**
     * Creates a serialization error.
     *
     * @param message the error message
     * @param cause the underlying cause
     * @return a new MemoryException
     */
    public static MemoryException serializationError(String message, Throwable cause) {
        return new MemoryException(message, cause, ErrorType.SERIALIZATION_ERROR, null, false);
    }

    /**
     * Types of memory errors.
     */
    public enum ErrorType {
        /** Unknown or unclassified error */
        UNKNOWN,

        /** Error in underlying storage (database, file system) */
        STORAGE_ERROR,

        /** Invalid input or parameters */
        VALIDATION_ERROR,

        /** Storage quota or capacity limit exceeded */
        QUOTA_EXCEEDED,

        /** Access permission denied */
        ACCESS_DENIED,

        /** Requested memory entry not found */
        NOT_FOUND,

        /** Error serializing or deserializing memory data */
        SERIALIZATION_ERROR,

        /** Operation timeout */
        TIMEOUT
    }
}
