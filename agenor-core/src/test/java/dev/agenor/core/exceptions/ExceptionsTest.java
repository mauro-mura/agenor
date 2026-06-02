package dev.agenor.core.exceptions;

import dev.agenor.core.config.ConfigurationException;
import dev.agenor.core.persistence.PersistenceException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Complete test coverage for the dev.agenor.core.exceptions package.
 */
class ExceptionsTest {

    // =========================================================================
    // JENTIC EXCEPTION TESTS (base class)
    // =========================================================================

    @Nested
    @DisplayName("AgenorException")
    class AgenorExceptionTest {

        @Test
        @DisplayName("Should create exception with message")
        void shouldCreateWithMessage() {
            String message = "Test exception message";
            AgenorException exception = new AgenorException(message);

            assertThat(exception.getMessage()).isEqualTo(message);
            assertThat(exception.getCause()).isNull();
        }

        @Test
        @DisplayName("Should create exception with message and cause")
        void shouldCreateWithMessageAndCause() {
            String message = "Test exception message";
            Throwable cause = new RuntimeException("Root cause");
            AgenorException exception = new AgenorException(message, cause);

            assertThat(exception.getMessage()).isEqualTo(message);
            assertThat(exception.getCause()).isEqualTo(cause);
        }
    }

    // =========================================================================
    // CONFIGURATION EXCEPTION TESTS
    // =========================================================================

    @Nested
    @DisplayName("ConfigurationException")
    class ConfigurationExceptionTest {

        @Test
        @DisplayName("Should create exception with message")
        void shouldCreateWithMessage() {
            String message = "Invalid configuration";
            ConfigurationException exception = new ConfigurationException(message);

            assertThat(exception.getMessage()).isEqualTo(message);
            assertThat(exception.getCause()).isNull();
        }

        @Test
        @DisplayName("Should create exception with message and cause")
        void shouldCreateWithMessageAndCause() {
            String message = "Configuration load failed";
            Throwable cause = new IllegalArgumentException("Invalid YAML");
            ConfigurationException exception = new ConfigurationException(message, cause);

            assertThat(exception.getMessage()).isEqualTo(message);
            assertThat(exception.getCause()).isEqualTo(cause);
        }
    }

    // =========================================================================
    // MESSAGE EXCEPTION TESTS
    // =========================================================================

    @Nested
    @DisplayName("MessageException")
    class MessageExceptionTest {

        @Test
        @DisplayName("Should create exception with messageId and message")
        void shouldCreateWithMessageId() {
            String messageId = "msg-12345";
            String message = "Message processing failed";
            MessageException exception = new MessageException(messageId, message);

            assertThat(exception.getMessage()).isEqualTo(message);
            assertThat(exception.getMessageId()).isEqualTo(messageId);
            assertThat(exception.getCause()).isNull();
        }

        @Test
        @DisplayName("Should create exception with messageId, message and cause")
        void shouldCreateWithMessageIdAndCause() {
            String messageId = "msg-67890";
            String message = "Message delivery failed";
            Throwable cause = new IllegalStateException("Queue full");
            MessageException exception = new MessageException(messageId, message, cause);

            assertThat(exception.getMessage()).isEqualTo(message);
            assertThat(exception.getMessageId()).isEqualTo(messageId);
            assertThat(exception.getCause()).isEqualTo(cause);
        }

        @Test
        @DisplayName("Should handle null messageId")
        void shouldHandleNullMessageId() {
            MessageException exception = new MessageException(null, "Test message");

            assertThat(exception.getMessageId()).isNull();
        }
    }

    // =========================================================================
    // AGENT EXCEPTION TESTS
    // =========================================================================

    @Nested
    @DisplayName("AgentException")
    class AgentExceptionTest {

        @Test
        @DisplayName("Should create exception with agentId and message")
        void shouldCreateWithAgentId() {
            String agentId = "agent-123";
            String message = "Agent initialization failed";
            AgentException exception = new AgentException(agentId, message);

            assertThat(exception.getMessage()).isEqualTo(message);
            assertThat(exception.getAgentId()).isEqualTo(agentId);
            assertThat(exception.getCause()).isNull();
        }

        @Test
        @DisplayName("Should create exception with agentId, message and cause")
        void shouldCreateWithAgentIdAndCause() {
            String agentId = "agent-456";
            String message = "Agent startup failed";
            Throwable cause = new NullPointerException("Missing dependency");
            AgentException exception = new AgentException(agentId, message, cause);

            assertThat(exception.getMessage()).isEqualTo(message);
            assertThat(exception.getAgentId()).isEqualTo(agentId);
            assertThat(exception.getCause()).isEqualTo(cause);
        }

        @Test
        @DisplayName("Should handle null agentId")
        void shouldHandleNullAgentId() {
            AgentException exception = new AgentException(null, "Test message");

            assertThat(exception.getAgentId()).isNull();
        }
    }

    // =========================================================================
    // AGENT NOT FOUND EXCEPTION TESTS (since 0.20.0)
    // =========================================================================

    @Nested
    @DisplayName("AgentNotFoundException")
    class AgentNotFoundExceptionTest {

        @Test
        @DisplayName("Should include agentId in message")
        void shouldIncludeAgentIdInMessage() {
            var ex = new AgentNotFoundException("my-agent");
            assertThat(ex.getMessage()).contains("my-agent");
            assertThat(ex.getAgentId()).isEqualTo("my-agent");
        }

        @Test
        @DisplayName("Should be a subtype of AgentException")
        void shouldExtendAgentException() {
            var ex = new AgentNotFoundException("agent-42");
            assertThat(ex).isInstanceOf(AgentException.class);
        }

        @Test
        @DisplayName("Should be catchable as RuntimeException")
        void shouldBeCatchableAsRuntimeException() {
            assertThatThrownBy(() -> { throw new AgentNotFoundException("x"); })
                    .isInstanceOf(RuntimeException.class);
        }
    }

    // =========================================================================
    // PERSISTENCE EXCEPTION TESTS
    // =========================================================================

    @Nested
    @DisplayName("PersistenceException")
    class PersistenceExceptionTest {

        @Test
        @DisplayName("Should create exception with agentId, operation and message")
        void shouldCreateWithAllParameters() {
            String agentId = "agent-789";
            PersistenceException.PersistenceOperation operation = PersistenceException.PersistenceOperation.SAVE;
            String message = "Failed to save agent state";

            PersistenceException exception = new PersistenceException(agentId, operation, message);

            assertThat(exception.getMessage()).isEqualTo(message);
            assertThat(exception.getAgentId()).isEqualTo(agentId);
            assertThat(exception.getOperation()).isEqualTo(operation);
            assertThat(exception.getCause()).isNull();
        }

        @Test
        @DisplayName("Should create exception with agentId, operation, message and cause")
        void shouldCreateWithCause() {
            String agentId = "agent-999";
            PersistenceException.PersistenceOperation operation = PersistenceException.PersistenceOperation.LOAD;
            String message = "Failed to load agent state";
            Throwable cause = new IllegalStateException("Database connection lost");

            PersistenceException exception = new PersistenceException(agentId, operation, message, cause);

            assertThat(exception.getMessage()).isEqualTo(message);
            assertThat(exception.getAgentId()).isEqualTo(agentId);
            assertThat(exception.getOperation()).isEqualTo(operation);
            assertThat(exception.getCause()).isEqualTo(cause);
        }

        @Test
        @DisplayName("Should work with all operation types")
        void shouldWorkWithAllOperations() {
            String agentId = "agent-test";

            for (PersistenceException.PersistenceOperation op : PersistenceException.PersistenceOperation.values()) {
                PersistenceException exception = new PersistenceException(agentId, op, "Test for " + op);

                assertThat(exception.getOperation()).isEqualTo(op);
            }
        }

        @Test
        @DisplayName("Should handle null agentId")
        void shouldHandleNullAgentId() {
            PersistenceException exception = new PersistenceException(
                null,
                PersistenceException.PersistenceOperation.DELETE,
                "Test message"
            );

            assertThat(exception.getAgentId()).isNull();
        }
    }

    // =========================================================================
    // PERSISTENCE OPERATION ENUM TESTS
    // =========================================================================

    @Nested
    @DisplayName("PersistenceOperation Enum")
    class PersistenceOperationTest {

        @Test
        @DisplayName("Should have all expected operations")
        void shouldHaveAllOperations() {
            PersistenceException.PersistenceOperation[] operations =
                PersistenceException.PersistenceOperation.values();

            assertThat(operations).containsExactlyInAnyOrder(
                PersistenceException.PersistenceOperation.SAVE,
                PersistenceException.PersistenceOperation.LOAD,
                PersistenceException.PersistenceOperation.DELETE,
                PersistenceException.PersistenceOperation.SNAPSHOT,
                PersistenceException.PersistenceOperation.RESTORE
            );
        }

        @Test
        @DisplayName("Should retrieve enum by name")
        void shouldRetrieveByName() {
            PersistenceException.PersistenceOperation save =
                PersistenceException.PersistenceOperation.valueOf("SAVE");

            assertThat(save).isEqualTo(PersistenceException.PersistenceOperation.SAVE);
        }

        @Test
        @DisplayName("Should have correct name for each operation")
        void shouldHaveCorrectNames() {
            assertThat(PersistenceException.PersistenceOperation.SAVE.name()).isEqualTo("SAVE");
            assertThat(PersistenceException.PersistenceOperation.LOAD.name()).isEqualTo("LOAD");
            assertThat(PersistenceException.PersistenceOperation.DELETE.name()).isEqualTo("DELETE");
            assertThat(PersistenceException.PersistenceOperation.SNAPSHOT.name()).isEqualTo("SNAPSHOT");
            assertThat(PersistenceException.PersistenceOperation.RESTORE.name()).isEqualTo("RESTORE");
        }
    }
}
