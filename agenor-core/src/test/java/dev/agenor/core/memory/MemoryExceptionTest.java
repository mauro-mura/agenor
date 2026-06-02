package dev.agenor.core.memory;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

@DisplayName("MemoryException Tests")
class MemoryExceptionTest {

    @Test
    @DisplayName("Should create exception with message")
    void testMessageOnly() {
        MemoryException exception = new MemoryException("Test error");

        assertThat(exception.getMessage()).contains("Test error");
        assertThat(exception.getErrorType()).isEqualTo(MemoryException.ErrorType.UNKNOWN);
        assertThat(exception.isRetryable()).isFalse();
    }

    @Test
    @DisplayName("Should create exception with cause")
    void testWithCause() {
        RuntimeException cause = new RuntimeException("Root cause");
        MemoryException exception = new MemoryException("Test error", cause);

        assertThat(exception.getMessage()).contains("Test error");
        assertThat(exception.getCause()).isEqualTo(cause);
        assertThat(exception.getErrorType()).isEqualTo(MemoryException.ErrorType.UNKNOWN);
    }

    @Test
    @DisplayName("Should create exception with full details")
    void testFullConstructor() {
        RuntimeException cause = new RuntimeException("Root cause");
        MemoryException exception = new MemoryException(
            "Test error",
            cause,
            MemoryException.ErrorType.STORAGE_ERROR,
            "TestStore",
            true
        );

        assertThat(exception.getMessage())
            .contains("TestStore")
            .contains("STORAGE_ERROR")
            .contains("Test error");
        assertThat(exception.getCause()).isEqualTo(cause);
        assertThat(exception.getErrorType()).isEqualTo(MemoryException.ErrorType.STORAGE_ERROR);
        assertThat(exception.getStoreName()).isEqualTo("TestStore");
        assertThat(exception.isRetryable()).isTrue();
    }

    @Test
    @DisplayName("Should create storage error")
    void testStorageError() {
        RuntimeException cause = new RuntimeException("DB connection failed");
        MemoryException exception = MemoryException.storageError(
            "DatabaseStore",
            "Failed to save entry",
            cause
        );

        assertThat(exception.getErrorType()).isEqualTo(MemoryException.ErrorType.STORAGE_ERROR);
        assertThat(exception.getStoreName()).isEqualTo("DatabaseStore");
        assertThat(exception.isRetryable()).isTrue();
        assertThat(exception.getCause()).isEqualTo(cause);
    }

    @Test
    @DisplayName("Should create validation error")
    void testValidationError() {
        MemoryException exception = MemoryException.validationError("Invalid key format");

        assertThat(exception.getErrorType()).isEqualTo(MemoryException.ErrorType.VALIDATION_ERROR);
        assertThat(exception.isRetryable()).isFalse();
        assertThat(exception.getMessage()).contains("Invalid key format");
    }

    @Test
    @DisplayName("Should create quota exceeded error")
    void testQuotaExceeded() {
        MemoryException exception = MemoryException.quotaExceeded(
            "InMemoryStore",
            "Maximum entries exceeded"
        );

        assertThat(exception.getErrorType()).isEqualTo(MemoryException.ErrorType.QUOTA_EXCEEDED);
        assertThat(exception.getStoreName()).isEqualTo("InMemoryStore");
        assertThat(exception.isRetryable()).isFalse();
    }

    @Test
    @DisplayName("Should create access denied error")
    void testAccessDenied() {
        MemoryException exception = MemoryException.accessDenied(
            "Agent does not have permission"
        );

        assertThat(exception.getErrorType()).isEqualTo(MemoryException.ErrorType.ACCESS_DENIED);
        assertThat(exception.isRetryable()).isFalse();
        assertThat(exception.getMessage()).contains("Agent does not have permission");
    }

    @Test
    @DisplayName("Should create not found error")
    void testNotFound() {
        MemoryException exception = MemoryException.notFound("memory-key-123");

        assertThat(exception.getErrorType()).isEqualTo(MemoryException.ErrorType.NOT_FOUND);
        assertThat(exception.isRetryable()).isFalse();
        assertThat(exception.getMessage()).contains("memory-key-123");
    }

    @Test
    @DisplayName("Should create serialization error")
    void testSerializationError() {
        RuntimeException cause = new RuntimeException("JSON parse failed");
        MemoryException exception = MemoryException.serializationError(
            "Failed to serialize entry",
            cause
        );

        assertThat(exception.getErrorType()).isEqualTo(MemoryException.ErrorType.SERIALIZATION_ERROR);
        assertThat(exception.isRetryable()).isFalse();
        assertThat(exception.getCause()).isEqualTo(cause);
    }

    @Test
    @DisplayName("Should handle all error types")
    void testAllErrorTypes() {
        MemoryException.ErrorType[] types = MemoryException.ErrorType.values();

        assertThat(types).containsExactlyInAnyOrder(
            MemoryException.ErrorType.UNKNOWN,
            MemoryException.ErrorType.STORAGE_ERROR,
            MemoryException.ErrorType.VALIDATION_ERROR,
            MemoryException.ErrorType.QUOTA_EXCEEDED,
            MemoryException.ErrorType.ACCESS_DENIED,
            MemoryException.ErrorType.NOT_FOUND,
            MemoryException.ErrorType.SERIALIZATION_ERROR,
            MemoryException.ErrorType.TIMEOUT
        );
    }

    @Test
    @DisplayName("Should format message with store name")
    void testMessageFormatting() {
        MemoryException exception = new MemoryException(
            "Test error",
            null,
            MemoryException.ErrorType.STORAGE_ERROR,
            "MyStore",
            false
        );

        assertThat(exception.getMessage())
            .startsWith("[MyStore:STORAGE_ERROR]")
            .endsWith("Test error");
    }

    @Test
    @DisplayName("Should format message without store name")
    void testMessageFormattingWithoutStore() {
        MemoryException exception = new MemoryException(
            "Test error",
            null,
            MemoryException.ErrorType.VALIDATION_ERROR,
            null,
            false
        );

        assertThat(exception.getMessage())
            .startsWith("[VALIDATION_ERROR]")
            .endsWith("Test error");
    }
}
