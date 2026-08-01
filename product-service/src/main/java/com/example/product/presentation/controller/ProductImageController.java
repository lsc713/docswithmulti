package com.example.product.presentation.controller;

import com.example.product.application.service.ProductImageService;
import com.example.product.common.exception.application.ForbiddenException;
import com.example.product.presentation.dto.ConfirmImageRequest;
import com.example.product.presentation.dto.ConfirmImageResponse;
import com.example.product.presentation.dto.PresignRequest;
import com.example.product.presentation.dto.PresignResponse;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 상품 이미지 업로드(presign) + 확정(confirm). 둘 다 ADMIN 전용 — 게이트웨이가 주입한 X-User-Role 재검증. */
@RestController
@RequestMapping("/v1/products/{id}/images")
public class ProductImageController {

    private final ProductImageService service;

    public ProductImageController(ProductImageService service) {
        this.service = service;
    }

    private static void requireAdmin(String role) {
        if (!"ADMIN".equals(role)) {
            throw new ForbiddenException();
        }
    }

    @PostMapping("/presign")
    public PresignResponse presign(@PathVariable Long id,
                                   @RequestHeader(value = "X-User-Role", required = false) String role,
                                   @RequestBody PresignRequest req) {
        requireAdmin(role);
        var p = service.presign(id, req.contentType());
        return new PresignResponse(p.key(), p.uploadUrl());
    }

    @PostMapping
    public ConfirmImageResponse confirm(@PathVariable Long id,
                                        @RequestHeader(value = "X-User-Role", required = false) String role,
                                        @RequestBody ConfirmImageRequest req) {
        requireAdmin(role);
        return new ConfirmImageResponse(service.confirm(id, req.key(), req.sortOrder()));
    }
}
