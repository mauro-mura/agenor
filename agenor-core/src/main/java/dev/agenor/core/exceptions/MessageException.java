package dev.agenor.core.exceptions;

/**
 * Exception thrown during message processing
 *
 * @deprecated since 0.28.0, for removal in 0.30.0. Nothing throws this, catches it, or
 *             declares it. The one apparent hit in the tree is a nested class of the same name
 *             declared locally inside {@code RetryExample}, which is a different type.
 */
@Deprecated(since = "0.28.0", forRemoval = true)
public class MessageException extends AgenorException {

    private final String messageId;

    public MessageException(String messageId, String message) {
        super(message);
        this.messageId = messageId;
    }

    public MessageException(String messageId, String message, Throwable cause) {
        super(message, cause);
        this.messageId = messageId;
    }

    public String getMessageId() {
        return messageId;
    }
}
