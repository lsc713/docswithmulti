package com.example.product.presentation.controller;

import com.example.product.application.usecase.GetProductUseCase;
import com.example.product.presentation.dto.InternalProductResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/products")
@RequiredArgsConstructor
public class InternalProductController {

    private final GetProductUseCase getProductUseCase;

    @GetMapping("/{id}")
    public ResponseEntity<InternalProductResponse> getProduct(@PathVariable long id) {
        GetProductUseCase.DetailResult detail = getProductUseCase.getDetail(id);
        return ResponseEntity.ok(
                InternalProductResponse.from(detail.product(), detail.currentVersion()));
    }
}
