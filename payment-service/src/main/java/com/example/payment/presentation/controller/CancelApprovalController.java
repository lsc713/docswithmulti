package com.example.payment.presentation.controller;

import com.example.payment.application.authz.AuthenticatedUser;
import com.example.payment.application.usecase.CancelApprovalUseCase;
import com.example.payment.domain.entity.CancelApprovalStatus;
import com.example.payment.presentation.dto.CancelApprovalListResponse;
import com.example.payment.presentation.dto.CancelApprovalResponse;
import com.example.payment.presentation.dto.CancelRejectRequest;
import com.example.payment.presentation.dto.CancelRequestCreateRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
public class CancelApprovalController {

    private final CancelApprovalUseCase useCase;

    @PostMapping("/v1/payments/{paymentKey}/cancel-requests")
    public ResponseEntity<CancelApprovalResponse> request(
        @PathVariable String paymentKey,
        @RequestBody @Valid CancelRequestCreateRequest body,
        @RequestHeader(value = "X-User-Role", required = false) String role,
        @RequestHeader(value = "X-User-Id", required = false) String userId,
        @RequestHeader(value = "X-Merchant-Id", required = false) String merchantId
    ) {
        AuthenticatedUser user = new AuthenticatedUser(userId, role, merchantId);
        var approval = useCase.request(paymentKey, user, body.reason());
        return ResponseEntity.status(HttpStatus.CREATED).body(CancelApprovalResponse.of(approval));
    }

    @GetMapping("/v1/cancel-requests")
    public CancelApprovalListResponse list(
        @RequestParam(defaultValue = "REQUESTED") CancelApprovalStatus status,
        @RequestHeader(value = "X-User-Role", required = false) String role,
        @RequestHeader(value = "X-User-Id", required = false) String userId,
        @RequestHeader(value = "X-Merchant-Id", required = false) String merchantId
    ) {
        AuthenticatedUser user = new AuthenticatedUser(userId, role, merchantId);
        return CancelApprovalListResponse.of(useCase.list(user, status));
    }

    @PostMapping("/v1/cancel-requests/{id}/approve")
    public CancelApprovalResponse approve(
        @PathVariable long id,
        @RequestHeader(value = "X-User-Role", required = false) String role,
        @RequestHeader(value = "X-User-Id", required = false) String userId,
        @RequestHeader(value = "X-Merchant-Id", required = false) String merchantId
    ) {
        AuthenticatedUser user = new AuthenticatedUser(userId, role, merchantId);
        return CancelApprovalResponse.of(useCase.approve(id, user));
    }

    @PostMapping("/v1/cancel-requests/{id}/reject")
    public CancelApprovalResponse reject(
        @PathVariable long id,
        @RequestBody @Valid CancelRejectRequest body,
        @RequestHeader(value = "X-User-Role", required = false) String role,
        @RequestHeader(value = "X-User-Id", required = false) String userId,
        @RequestHeader(value = "X-Merchant-Id", required = false) String merchantId
    ) {
        AuthenticatedUser user = new AuthenticatedUser(userId, role, merchantId);
        return CancelApprovalResponse.of(useCase.reject(id, user, body.decisionReason()));
    }
}
