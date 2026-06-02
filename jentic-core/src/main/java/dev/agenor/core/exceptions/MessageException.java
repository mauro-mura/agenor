package dev.agenor.core.exceptions;

/**
 * Exception thrown during message processing
 */
public class MessageException extends JenticException {

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
