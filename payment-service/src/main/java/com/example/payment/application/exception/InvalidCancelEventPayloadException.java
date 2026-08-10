package com.example.payment.application.exception;

public class InvalidCancelEventPayloadException extends RuntimeException {
    public InvalidCancelEventPayloadException(String message) {
        super(message);
    }

    public InvalidCancelEventPayloadException(String message, Throwable cause) {
        super(message, cause);
    }
}
