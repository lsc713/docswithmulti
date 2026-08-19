package com.example.settlement.presentation.controller;

import com.example.settlement.application.service.PayoutService;
import com.example.settlement.application.service.SettlementQueryService;
import com.example.settlement.common.exception.BusinessException;
import com.example.settlement.common.exception.ErrorCode;
import com.example.settlement.domain.entity.Payout;
import com.example.settlement.presentation.dto.PayoutResponse;
import com.example.settlement.presentation.dto.SettlementDetailResponse;
import com.example.settlement.presentation.dto.SettlementResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/v1/admin/settlements")
public class AdminSettlementController {

    private final SettlementQueryService queries;
    private final PayoutService payouts;

    public AdminSettlementController(SettlementQueryService queries, PayoutService payouts) {
        this.queries = queries;
        this.payouts = payouts;
    }

    @GetMapping
    public List<SettlementResponse> list(
            @RequestHeader(value = "X-User-Role", required = false) String role,
            @RequestParam long merchantId,
            @RequestParam(required = false) String status) {
        requireAdmin(role);
        return queries.list(merchantId, status).stream().map(SettlementResponse::from).toList();
    }

    @GetMapping("/{id}")
    public SettlementDetailResponse detail(
            @RequestHeader(value = "X-User-Role", required = false) String role,
            @PathVariable long id) {
        requireAdmin(role);
        return SettlementDetailResponse.from(queries.detail(id));
    }

    @PostMapping("/{id}/payout")
    public PayoutResponse approvePayout(
            @RequestHeader(value = "X-User-Role", required = false) String role,
            @PathVariable long id) {
        requireAdmin(role);
        Payout payout = payouts.approve(id);
        return new PayoutResponse(payout.getId(), payout.getStatus(), payout.getAmount());
    }

    private static void requireAdmin(String role) {
        if (!"ADMIN".equals(role)) throw new ForbiddenException();
    }

    private static final class ForbiddenException extends BusinessException {
        private ForbiddenException() { super(ErrorCode.FORBIDDEN); }
    }
}
