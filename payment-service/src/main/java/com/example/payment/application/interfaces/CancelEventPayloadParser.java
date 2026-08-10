package com.example.payment.application.interfaces;

import com.example.payment.application.model.CancelEventPayload;

public interface CancelEventPayloadParser {
    CancelEventPayload parse(String payload);
}
