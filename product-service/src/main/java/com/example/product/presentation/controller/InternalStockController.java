package com.example.product.presentation.controller;

import com.example.product.application.usecase.StockDeductUseCase;
import com.example.product.presentation.dto.StockDeductRequest;
import com.example.product.presentation.dto.StockDeductResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/stocks")
@RequiredArgsConstructor
public class InternalStockController {

    private final StockDeductUseCase stockDeductUseCase;

    @PostMapping("/deduct")
    public ResponseEntity<StockDeductResponse> deduct(@RequestBody StockDeductRequest request) {
        StockDeductUseCase.Result result = stockDeductUseCase.execute(
                new StockDeductUseCase.Command(request.skuId(), request.quantity()));
        return ResponseEntity.ok(new StockDeductResponse(result.skuId(), result.remainingQuantity()));
    }
}
