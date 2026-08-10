package com.example.product.application.service;

import com.example.product.application.interfaces.ProcessedCancelEventRepository;
import com.example.product.application.interfaces.StockReservationRepository;
import com.example.product.application.model.CancelRestoreLegStatus;
import com.example.product.application.usecase.InspectCancelRestoreUseCase;
import com.example.product.domain.entity.ReservationStatus;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class InspectCancelRestoreService implements InspectCancelRestoreUseCase {

    private final ProcessedCancelEventRepository processedCancelEventRepository;
    private final StockReservationRepository stockReservationRepository;

    public InspectCancelRestoreService(
        ProcessedCancelEventRepository processedCancelEventRepository,
        StockReservationRepository stockReservationRepository
    ) {
        this.processedCancelEventRepository = processedCancelEventRepository;
        this.stockReservationRepository = stockReservationRepository;
    }

    @Override
    public Result inspect(Command command) {
        Evidence duplicate = findDuplicate(command.items());
        if (duplicate != null) {
            return inconsistent(List.of(duplicate));
        }

        boolean processed = processedCancelEventRepository
            .existsByCancelRequestId(command.cancelRequestId());
        ReservationStatus expectedStatus = processed
            ? ReservationStatus.RELEASED
            : ReservationStatus.RESERVED;
        List<Evidence> evidence = new ArrayList<>();

        for (Item item : command.items()) {
            stockReservationRepository
                .findByPaymentKeyAndSkuId(command.paymentKey(), item.skuId())
                .ifPresentOrElse(reservation -> {
                    if (reservation.getStatus() != expectedStatus
                        || reservation.getQty() != item.quantity()) {
                        evidence.add(new Evidence(
                            item.skuId(), reservation.getStatus().name(),
                            reservation.getQty(), item.quantity()));
                    }
                }, () -> evidence.add(new Evidence(
                    item.skuId(), "MISSING", null, item.quantity())));
        }

        if (!evidence.isEmpty()) {
            return inconsistent(evidence);
        }
        return new Result(
            processed ? CancelRestoreLegStatus.APPLIED : CancelRestoreLegStatus.NOT_APPLIED,
            List.of());
    }

    private Evidence findDuplicate(List<Item> items) {
        Set<Long> seen = new HashSet<>();
        for (Item item : items) {
            if (!seen.add(item.skuId())) {
                return new Evidence(item.skuId(), "DUPLICATE", null, item.quantity());
            }
        }
        return null;
    }

    private Result inconsistent(List<Evidence> evidence) {
        return new Result(CancelRestoreLegStatus.INCONSISTENT, evidence);
    }
}
