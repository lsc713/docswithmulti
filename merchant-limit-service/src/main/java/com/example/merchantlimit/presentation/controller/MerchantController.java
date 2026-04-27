package com.example.merchantlimit.presentation.controller;

import com.example.merchantlimit.application.service.CreateMerchantCommand;
import com.example.merchantlimit.application.usecase.CreateMerchantUseCase;
import com.example.merchantlimit.domain.entity.Merchant;
import com.example.merchantlimit.presentation.dto.CreateMerchantV1Request;
import com.example.merchantlimit.presentation.dto.CreateMerchantV1Response;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

@RestController
@RequestMapping("/v1/merchants")
@RequiredArgsConstructor
public class MerchantController {

    private final CreateMerchantUseCase createMerchantUseCase;

    @PostMapping
    public ResponseEntity<CreateMerchantV1Response> create(
        @RequestBody @Valid CreateMerchantV1Request request
    ) {
        CreateMerchantCommand command = new CreateMerchantCommand(
            request.merchantKey(),
            request.name(),
            request.cancelPeriodDays(),
            request.dailyLimit()
        );
        Merchant merchant = createMerchantUseCase.create(command);
        return ResponseEntity
            .created(URI.create("/v1/merchants/" + merchant.getId()))
            .body(CreateMerchantV1Response.from(merchant));
    }
}
