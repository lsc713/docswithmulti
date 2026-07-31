package com.example.order.presentation.controller;

import com.example.order.application.service.CreateOrderCommand;
import com.example.order.application.usecase.CreateOrderUseCase;
import com.example.order.application.usecase.VerifyOrderItemsUseCase;
import com.example.order.presentation.dto.CreateOrderRequest;
import com.example.order.presentation.dto.CreateOrderResponse;
import com.example.order.presentation.dto.VerifyOrderItemsRequest;
import com.example.order.presentation.dto.VerifyOrderItemsResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

@RestController
@RequestMapping("/v1/orders")
@RequiredArgsConstructor
public class OrderController {

    private final CreateOrderUseCase createOrderUseCase;
    private final VerifyOrderItemsUseCase verifyOrderItemsUseCase;

    @PostMapping
    public ResponseEntity<CreateOrderResponse> create(
        @RequestBody @Valid CreateOrderRequest request
    ) {
        CreateOrderCommand command = new CreateOrderCommand(
            request.userId(),
            request.items().stream()
                .map(item -> new CreateOrderCommand.Item(
                    item.productId(), item.itemName(), item.price()))
                .toList()
        );

        CreateOrderUseCase.Result result = createOrderUseCase.create(command);
        return ResponseEntity
            .created(URI.create("/v1/orders/" + result.order().getId()))
            .body(CreateOrderResponse.from(result));
    }

    /**
     * 내부 전용 검증 엔드포인트(payment가 호출, 게이트웨이 미노출 — Task 4 exact predicate).
     * 컨트롤러는 헤더/DTO 매핑만 수행하고 판정은 도메인(OrderItemVerifier)에 위임(D-CONTEXT-3).
     */
    @PostMapping("/items:verify")
    public ResponseEntity<VerifyOrderItemsResponse> verify(
        @RequestHeader("X-User-Id") long userId,
        @RequestBody @Valid VerifyOrderItemsRequest request
    ) {
        VerifyOrderItemsUseCase.Result result = verifyOrderItemsUseCase.verify(request.orderItemIds(), userId);
        return ResponseEntity.ok(VerifyOrderItemsResponse.from(result));
    }
}
