package com.example.settlement.presentation.controller;

import com.example.settlement.application.service.ReserveQueryService;
import com.example.settlement.domain.entity.Reserve;
import com.example.settlement.presentation.dto.ReserveResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 유보 상태 조회 (HOLD-04). 없으면 RESERVE_NOT_FOUND(404, GlobalExceptionHandler). PayoutController.payout 미러.
 */
@RestController
@RequestMapping("/v1/settlements")
@RequiredArgsConstructor
public class ReserveQueryController {

    private final ReserveQueryService service;

    @GetMapping("/{id}/reserve")
    public ReserveResponse reserve(@PathVariable long id) {
        Reserve r = service.getReserve(id);
        return new ReserveResponse(
            r.getId(), r.getStatus(), r.getAmount(), r.getHoldUntil(), r.getTransferRef());
    }
}
