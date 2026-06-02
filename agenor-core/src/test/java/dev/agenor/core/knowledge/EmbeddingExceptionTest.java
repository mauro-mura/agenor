package dev.agenor.core.knowledge;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.*;

class EmbeddingExceptionTest {

    @ParameterizedTest
    @EnumSource(EmbeddingException.ErrorType.class)
    @DisplayName("getErrorType returns the type supplied at construction (no cause)")
    void getErrorTypeNoCause(EmbeddingException.ErrorType type) {
        var ex = new EmbeddingException("msg", type);
        assertThat(ex.getErrorType()).isEqualTo(type);
        assertThat(ex.getMessage()).isEqualTo("msg");
        assertThat(ex.getCause()).isNull();
    }

    @ParameterizedTest
    @EnumSource(EmbeddingException.ErrorType.class)
    @DisplayName("getErrorType returns the type supplied at construction (with cause)")
    void getErrorTypeWithCause(EmbeddingException.ErrorType type) {
        var cause = new RuntimeException("root");
        var ex = new EmbeddingException("msg", type, cause);
        assertThat(ex.getErrorType()).isEqualTo(type);
        assertThat(ex.getCause()).isSameAs(cause);
    }

    @Test
    @DisplayName("is a RuntimeException (unchecked)")
    void isRuntimeException() {
        assertThat(new EmbeddingException("x", EmbeddingException.ErrorType.UNKNOWN))
            .isInstanceOf(RuntimeException.class);
    }
}
