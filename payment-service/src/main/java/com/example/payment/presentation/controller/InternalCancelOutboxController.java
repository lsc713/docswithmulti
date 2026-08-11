package com.example.payment.presentation.controller;

import com.example.payment.application.authz.InternalOperatorAccess;
import com.example.payment.application.usecase.CancelOutboxRedriveQuery;
import com.example.payment.application.usecase.CancelOutboxRedriveUseCase;
import com.example.payment.application.usecase.CancelOutboxInspectionUseCase;
import com.example.payment.presentation.dto.CancelOutboxInspectionResponse;
import com.example.payment.presentation.dto.CancelOutboxRedriveRequest;
import com.example.payment.presentation.dto.CancelOutboxRedriveResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@ConditionalOnProperty(name = "cancel.publish.mode", havingValue = "OUTBOX", matchIfMissing = true)
public class InternalCancelOutboxController {

    private final CancelOutboxInspectionUseCase useCase;
    private final CancelOutboxRedriveUseCase redriveUseCase;
    private final CancelOutboxRedriveQuery redriveQuery;
    private final InternalOperatorAccess operatorAccess;

    @GetMapping("/internal/cancel-outbox/{outboxId}")
    public CancelOutboxInspectionResponse inspect(
        @PathVariable long outboxId,
        @RequestHeader(value = "X-User-Role", required = false) String role,
        @RequestHeader(value = "X-User-Id", required = false) String operatorId
    ) {
        operatorAccess.requireAdmin(role, operatorId);
        return CancelOutboxInspectionResponse.from(useCase.inspect(outboxId));
    }

    @PostMapping("/internal/cancel-outbox/{outboxId}/redrives")
    public ResponseEntity<CancelOutboxRedriveResponse> requestRedrive(
        @PathVariable long outboxId,
        @RequestHeader(value = "X-User-Role", required = false) String role,
        @RequestHeader(value = "X-User-Id", required = false) String operatorId,
        @RequestBody CancelOutboxRedriveRequest request
    ) {
        operatorAccess.requireAdmin(role, operatorId);
        return ResponseEntity.accepted().body(CancelOutboxRedriveResponse.from(
            redriveUseCase.request(outboxId, operatorId, request.reason())));
    }

    @GetMapping("/internal/cancel-outbox/redrives/{redriveId}")
    public CancelOutboxRedriveResponse getRedrive(
        @PathVariable long redriveId,
        @RequestHeader(value = "X-User-Role", required = false) String role,
        @RequestHeader(value = "X-User-Id", required = false) String operatorId
    ) {
        operatorAccess.requireAdmin(role, operatorId);
        return CancelOutboxRedriveResponse.from(redriveQuery.get(redriveId));
    }
}
