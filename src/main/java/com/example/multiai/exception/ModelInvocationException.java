package com.example.multiai.exception;

import com.example.multiai.enums.ModelProvider;

public class ModelInvocationException extends RuntimeException {

    private final ModelProvider provider;

    public ModelInvocationException(ModelProvider provider, String message) {
        super(message);
        this.provider = provider;
    }

    public ModelInvocationException(ModelProvider provider, String message, Throwable cause) {
        super(message, cause);
        this.provider = provider;
    }

    public ModelProvider getProvider() {
        return provider;
    }
}
