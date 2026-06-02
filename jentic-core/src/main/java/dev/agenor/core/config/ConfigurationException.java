package dev.agenor.core.config;

import dev.agenor.core.exceptions.JenticException;

public class ConfigurationException extends JenticException {
        public ConfigurationException(String message) {
            super(message);
        }

        public ConfigurationException(String message, Throwable cause) {
            super(message, cause);
        }
    }
