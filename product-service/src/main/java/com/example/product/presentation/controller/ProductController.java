package com.example.product.presentation.controller;

import com.example.product.application.usecase.CreateProductUseCase;
import com.example.product.application.usecase.GetProductUseCase;
import com.example.product.application.usecase.ProductVersionUseCase;
import com.example.product.application.usecase.SkuUseCase;
import com.example.product.common.exception.application.ForbiddenProductException;
import com.example.product.domain.entity.Product;
import com.example.product.domain.entity.ProductSku;
import com.example.product.domain.entity.ProductVersion;
import com.example.product.infrastructure.security.AuthenticatedUser;
import com.example.product.presentation.dto.*;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/v1/products")
@RequiredArgsConstructor
public class ProductController {

    private final CreateProductUseCase createProductUseCase;
    private final GetProductUseCase getProductUseCase;
    private final ProductVersionUseCase productVersionUseCase;
    private final SkuUseCase skuUseCase;

    @PostMapping
    public ResponseEntity<ProductDetailResponse> create(Authentication authentication,
                                                        @Valid @RequestBody CreateProductRequest request) {
        AuthenticatedUser user = (AuthenticatedUser) authentication.getPrincipal();
        CreateProductUseCase.Command command = new CreateProductUseCase.Command(
                user.merchantId(),
                request.categoryId(),
                request.name(),
                request.price(),
                request.discountPrice(),
                request.attributes(),
                request.skus().stream()
                        .map(s -> new CreateProductUseCase.SkuInput(s.color(), s.size(), s.quantity()))
                        .toList()
        );
        CreateProductUseCase.Result result = createProductUseCase.execute(command);

        // Build detail response from result
        GetProductUseCase.DetailResult detail = getProductUseCase.getDetail(result.product().getId());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ProductDetailResponse.from(detail.product(), detail.currentVersion(), detail.skus()));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ProductDetailResponse> getDetail(@PathVariable long id) {
        GetProductUseCase.DetailResult detail = getProductUseCase.getDetail(id);
        return ResponseEntity.ok(
                ProductDetailResponse.from(detail.product(), detail.currentVersion(), detail.skus()));
    }

    @GetMapping
    public ResponseEntity<List<ProductListResponse>> list(
            @RequestParam(required = false) Long categoryId,
            @RequestParam(required = false) Long merchantId) {
        List<ProductListResponse> responses = getProductUseCase.list(categoryId, merchantId).stream()
                .map(ProductListResponse::from)
                .toList();
        return ResponseEntity.ok(responses);
    }

    @PatchMapping("/{id}/status")
    public ResponseEntity<ProductListResponse> updateStatus(Authentication authentication,
                                                            @PathVariable long id,
                                                            @Valid @RequestBody UpdateProductStatusRequest request) {
        AuthenticatedUser user = (AuthenticatedUser) authentication.getPrincipal();
        // Check ownership
        Product product = getProductUseCase.getDetail(id).product();
        if (!"ADMIN".equals(user.role()) && (user.merchantId() == null || user.merchantId() != product.getMerchantId())) {
            throw new ForbiddenProductException();
        }
        Product updated = getProductUseCase.updateStatus(id, request.status());
        return ResponseEntity.ok(ProductListResponse.from(updated));
    }

    @PostMapping("/{id}/versions")
    public ResponseEntity<VersionResponse> createVersion(Authentication authentication,
                                                         @PathVariable long id,
                                                         @Valid @RequestBody CreateVersionRequest request) {
        AuthenticatedUser user = (AuthenticatedUser) authentication.getPrincipal();
        Product product = getProductUseCase.getDetail(id).product();
        if (!"ADMIN".equals(user.role()) && (user.merchantId() == null || user.merchantId() != product.getMerchantId())) {
            throw new ForbiddenProductException();
        }
        ProductVersion version = productVersionUseCase.createVersion(id,
                new ProductVersionUseCase.Command(request.name(), request.price(), request.discountPrice(), request.attributes()));
        return ResponseEntity.status(HttpStatus.CREATED).body(VersionResponse.from(version));
    }

    @GetMapping("/{id}/versions")
    public ResponseEntity<List<VersionResponse>> getVersions(@PathVariable long id) {
        List<VersionResponse> responses = productVersionUseCase.getVersions(id).stream()
                .map(VersionResponse::from)
                .toList();
        return ResponseEntity.ok(responses);
    }

    @PostMapping("/{id}/skus")
    public ResponseEntity<ProductDetailResponse> addSku(Authentication authentication,
                                                        @PathVariable long id,
                                                        @Valid @RequestBody CreateSkuRequest request) {
        AuthenticatedUser user = (AuthenticatedUser) authentication.getPrincipal();
        Product product = getProductUseCase.getDetail(id).product();
        if (!"ADMIN".equals(user.role()) && (user.merchantId() == null || user.merchantId() != product.getMerchantId())) {
            throw new ForbiddenProductException();
        }
        ProductSku sku = skuUseCase.addSku(id,
                new SkuUseCase.CreateCommand(request.color(), request.size(), request.initialQuantity()));
        GetProductUseCase.DetailResult detail = getProductUseCase.getDetail(id);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ProductDetailResponse.from(detail.product(), detail.currentVersion(), detail.skus()));
    }

    @PatchMapping("/skus/{skuId}/stock")
    public ResponseEntity<Void> updateStock(@PathVariable long skuId,
                                            @RequestBody UpdateStockRequest request) {
        skuUseCase.updateStock(skuId, request.quantity());
        return ResponseEntity.noContent().build();
    }
}
