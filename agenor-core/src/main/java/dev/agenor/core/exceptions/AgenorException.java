package dev.agenor.core.exceptions;

/**
 * Base exception for all Agenor framework exceptions
 */
public class AgenorException extends RuntimeException {

    public AgenorException(String message) {
        super(message);
    }

    public AgenorException(String message, Throwable cause) {
        super(message, cause);
    }
}
