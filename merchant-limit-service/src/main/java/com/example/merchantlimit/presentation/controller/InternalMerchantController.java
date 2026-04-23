package com.example.merchantlimit.presentation.controller;

import com.example.merchantlimit.application.usecase.GetCancelLimitUseCase;
import com.example.merchantlimit.presentation.dto.CancelLimitResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/internal/merchants")
@RequiredArgsConstructor
public class InternalMerchantController {

    private final GetCancelLimitUseCase getCancelLimitUseCase;

    @GetMapping("/{merchantId}/cancel-limit")
    public ResponseEntity<CancelLimitResponse> getCancelLimit(
        @PathVariable long merchantId
    ) {
        GetCancelLimitUseCase.Result result = getCancelLimitUseCase.execute(merchantId);
        return ResponseEntity.ok(new CancelLimitResponse(
            result.merchantId(), result.dailyLimit(), result.merchantStatus()
        ));
    }
}
