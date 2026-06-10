package com.example.multiai.exception;

public class NoAvailableModelException extends RuntimeException {

    public NoAvailableModelException(String message) {
        super(message);
    }
}
