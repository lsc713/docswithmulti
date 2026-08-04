package com.example.order.presentation.controller;

import com.example.order.application.usecase.CartUseCase;
import com.example.order.presentation.dto.AddCartItemRequest;
import com.example.order.presentation.dto.CartResponse;
import com.example.order.presentation.dto.UpdateQuantityRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/v1/cart")
@RequiredArgsConstructor
public class CartController {

    private final CartUseCase cartUseCase;

    @GetMapping
    public CartResponse get(@RequestHeader("X-User-Id") long userId) {
        return CartResponse.from(cartUseCase.getCart(userId));
    }

    @PostMapping("/items")
    public ResponseEntity<CartResponse> add(@RequestHeader("X-User-Id") long userId,
                                            @RequestBody @Valid AddCartItemRequest req) {
        cartUseCase.addItem(userId, new CartUseCase.AddCommand(
            req.skuId(), req.productId(), req.itemName(), req.optionSummary(), req.unitPrice(), req.quantity()));
        return ResponseEntity.status(HttpStatus.CREATED).body(CartResponse.from(cartUseCase.getCart(userId)));
    }

    @PatchMapping("/items/{skuId}")
    public CartResponse update(@RequestHeader("X-User-Id") long userId, @PathVariable long skuId,
                               @RequestBody @Valid UpdateQuantityRequest req) {
        cartUseCase.updateQuantity(userId, skuId, req.quantity());
        return CartResponse.from(cartUseCase.getCart(userId));
    }

    @DeleteMapping("/items/{skuId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void remove(@RequestHeader("X-User-Id") long userId, @PathVariable long skuId) {
        cartUseCase.removeItem(userId, skuId);
    }

    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void clear(@RequestHeader("X-User-Id") long userId) {
        cartUseCase.clear(userId);
    }
}
