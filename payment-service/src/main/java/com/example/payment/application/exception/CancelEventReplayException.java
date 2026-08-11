package com.example.payment.application.exception;

public final class CancelEventReplayException extends RuntimeException {

    public enum Kind { TIMEOUT, SEND_FAILED }

    private final Kind kind;

    public CancelEventReplayException(long cancelRequestId, Kind kind, Throwable cause) {
        super("Cancel event replay failed. cancelRequestId=" + cancelRequestId, cause);
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }
}
