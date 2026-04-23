package com.example.riskmanagement.presentation.controller;

import com.example.riskmanagement.application.usecase.CheckChargeUseCase;
import com.example.riskmanagement.application.usecase.CompensateUseCase;
import com.example.riskmanagement.application.usecase.ValidateAndReserveUseCase;
import com.example.riskmanagement.presentation.dto.*;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/internal/cancel-limit")
@RequiredArgsConstructor
public class InternalCancelLimitController {

    private final ValidateAndReserveUseCase validateAndReserveUseCase;
    private final CompensateUseCase compensateUseCase;
    private final CheckChargeUseCase checkChargeUseCase;

    @PostMapping("/validate-and-reserve")
    public ResponseEntity<ValidateAndReserveResponse> validateAndReserve(
        @RequestBody @Valid ValidateAndReserveRequest request) {
        return ResponseEntity.ok(
            ValidateAndReserveResponse.from(validateAndReserveUseCase.execute(request.toCommand())));
    }

    @PostMapping("/compensate")
    public ResponseEntity<CompensateResponse> compensate(
        @RequestBody @Valid CompensateRequest request) {
        return ResponseEntity.ok(
            CompensateResponse.from(compensateUseCase.execute(request.toCommand())));
    }

    @GetMapping("/check")
    public ResponseEntity<CheckChargeResponse> check(@RequestParam String cancelRequestId) {
        return ResponseEntity.ok(
            CheckChargeResponse.from(checkChargeUseCase.execute(cancelRequestId)));
    }
}
