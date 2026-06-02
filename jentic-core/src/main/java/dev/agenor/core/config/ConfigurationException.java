package dev.agenor.core.config;

import dev.agenor.core.exceptions.AgenorException;

public class ConfigurationException extends AgenorException {
        public ConfigurationException(String message) {
            super(message);
        }

        public ConfigurationException(String message, Throwable cause) {
            super(message, cause);
        }
    }
