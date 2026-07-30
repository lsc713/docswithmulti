package com.example.product.presentation.controller;

import com.example.product.application.service.StockService;
import com.example.product.application.service.StockService.ReserveItem;
import com.example.product.presentation.dto.ReleaseRequest;
import com.example.product.presentation.dto.ReleaseResponse;
import com.example.product.presentation.dto.ReserveRequest;
import com.example.product.presentation.dto.ReserveResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/stock")
public class StockController {

    private final StockService stockService;

    public StockController(StockService stockService) {
        this.stockService = stockService;
    }

    @PostMapping("/reserve")
    public ReserveResponse reserve(@Valid @RequestBody ReserveRequest req) {
        var items = req.items().stream()
                .map(i -> new ReserveItem(i.skuId(), i.qty()))
                .toList();
        stockService.reserve(req.paymentKey(), items);
        return ReserveResponse.ok();
    }

    @PostMapping("/release")
    public ReleaseResponse release(@Valid @RequestBody ReleaseRequest req) {
        var items = req.items().stream()
                .map(i -> new ReserveItem(i.skuId(), i.qty()))
                .toList();
        stockService.release(req.paymentKey(), items);
        return ReleaseResponse.ok();
    }
}
