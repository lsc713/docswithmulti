package com.example.order.presentation.controller;

import com.example.order.application.service.CreateOrderCommand;
import com.example.order.application.usecase.CreateOrderUseCase;
import com.example.order.presentation.dto.CreateOrderRequest;
import com.example.order.presentation.dto.CreateOrderResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

@RestController
@RequestMapping("/v1/orders")
@RequiredArgsConstructor
public class OrderController {

    private final CreateOrderUseCase createOrderUseCase;

    @PostMapping
    public ResponseEntity<CreateOrderResponse> create(
        @RequestBody @Valid CreateOrderRequest request
    ) {
        CreateOrderCommand command = new CreateOrderCommand(
            request.userId(),
            request.items().stream()
                .map(item -> new CreateOrderCommand.Item(
                    item.productId(), item.skuId(), item.quantity(), item.itemName(), item.price()))
                .toList()
        );

        CreateOrderUseCase.Result result = createOrderUseCase.create(command);
        return ResponseEntity
            .created(URI.create("/v1/orders/" + result.order().getId()))
            .body(CreateOrderResponse.from(result));
    }
}
