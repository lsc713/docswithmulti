package com.example.merchantlimit.presentation.controller;

import com.example.merchantlimit.application.interfaces.LimitHistoryRepository;
import com.example.merchantlimit.application.interfaces.MerchantRepository;
import com.example.merchantlimit.application.usecase.UpdateCancelLimitUseCase;
import com.example.merchantlimit.common.exception.BusinessException;
import com.example.merchantlimit.common.exception.ErrorCode;
import com.example.merchantlimit.domain.entity.Merchant;
import com.example.merchantlimit.domain.entity.MerchantStatus;
import com.example.merchantlimit.common.exception.domain.MerchantNotFoundException;
import com.example.merchantlimit.presentation.dto.*;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;

@RestController
@RequestMapping("/admin/merchants")
@RequiredArgsConstructor
public class AdminMerchantController {

    private final MerchantRepository merchantRepository;
    private final UpdateCancelLimitUseCase updateCancelLimitUseCase;
    private final LimitHistoryRepository limitHistoryRepository;

    @PostMapping
    public ResponseEntity<MerchantResponse> createMerchant(
        @RequestBody @Valid CreateMerchantRequest request
    ) {
        if (merchantRepository.existsByMerchantKey(request.merchantKey())) {
            throw new MerchantKeyDuplicatedException(request.merchantKey());
        }
        Merchant merchant = merchantRepository.save(
            Merchant.create(request.merchantKey(), request.name(), request.cancelPeriodDays())
        );
        return ResponseEntity
            .created(URI.create("/admin/merchants/" + merchant.getId()))
            .body(MerchantResponse.from(merchant));
    }

    @PatchMapping("/{merchantId}/status")
    public ResponseEntity<MerchantResponse> updateStatus(
        @PathVariable long merchantId,
        @RequestBody @Valid PatchMerchantStatusRequest request
    ) {
        Merchant merchant = merchantRepository.findById(merchantId)
            .orElseThrow(() -> new MerchantNotFoundException(merchantId));

        MerchantStatus newStatus = MerchantStatus.valueOf(request.status());
        switch (newStatus) {
            case ACTIVE -> merchant.activate();
            case INACTIVE -> merchant.deactivate();
            case SUSPENDED -> merchant.suspend();
        }
        return ResponseEntity.ok(MerchantResponse.from(merchantRepository.save(merchant)));
    }

    @PutMapping("/{merchantId}/cancel-limit")
    public ResponseEntity<CancelLimitResponse> updateLimit(
        @PathVariable long merchantId,
        @RequestBody @Valid UpdateLimitRequest request
    ) {
        UpdateCancelLimitUseCase.Result result =
            updateCancelLimitUseCase.execute(merchantId, request.dailyLimit(), request.reason());
        return ResponseEntity.ok(new CancelLimitResponse(result.merchantId(), result.dailyLimit(), null));
    }

    @GetMapping("/{merchantId}/cancel-limit/history")
    public ResponseEntity<LimitHistoryPageResponse> getLimitHistory(
        @PathVariable long merchantId,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size
    ) {
        var historyPage = limitHistoryRepository.findByMerchantId(
            merchantId, PageRequest.of(page, size));
        return ResponseEntity.ok(LimitHistoryPageResponse.from(historyPage));
    }

    @GetMapping
    public ResponseEntity<MerchantPageResponse> listMerchants(
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size
    ) {
        var result = merchantRepository.findAll(PageRequest.of(page, size));
        return ResponseEntity.ok(MerchantPageResponse.from(result));
    }

    static class MerchantKeyDuplicatedException extends BusinessException {
        MerchantKeyDuplicatedException(String key) {
            super(ErrorCode.MERCHANT_KEY_DUPLICATED, "이미 사용 중인 가맹점 키입니다. key=" + key);
        }
    }
}
