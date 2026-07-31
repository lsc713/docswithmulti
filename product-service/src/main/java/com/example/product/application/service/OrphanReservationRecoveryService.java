package com.example.product.application.service;

import com.example.product.application.interfaces.PaymentQueryPort;
import com.example.product.application.interfaces.StockReservationRepository;
import com.example.product.domain.entity.StockReservation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * orphan 예약 복구 (RST-03, D-P3-3/D-P3-4).
 *
 * <p>reserve 성공 후 결제가 커밋되지 않아(생성 실패 보상마저 유실) 영구 RESERVED로 묶인 예약을 정리하는 backstop.
 * threshold(5분+)보다 오래된 RESERVED 를 스캔 → paymentKey별로 payment-service exists 조회(권위) →
 * 커밋 payment 없음(exists=false)일 때만 release 한다.
 *
 * <p><b>fail-safe</b>: 조회 예외(장애/타임아웃)는 그 paymentKey skip + warn(다음 주기 재시도). 조회 불가를
 * "미존재"로 오인해 release 하면 정상 예약이 조기 복원되는 재고 오류 → 예외 시 절대 release 금지.
 * <p><b>멱등</b>: release 는 Phase 1 원자 조건부 전이(RESERVED→RELEASED)라 재실행/중복이 재고를 오염시키지 않는다.
 */
@Slf4j
@Service
public class OrphanReservationRecoveryService {

    private final StockReservationRepository reservationRepository;
    private final PaymentQueryPort paymentQueryPort;
    private final StockService stockService;

    @Value("${orphan.threshold-minutes}")
    private long thresholdMinutes;

    public OrphanReservationRecoveryService(StockReservationRepository reservationRepository,
                                            PaymentQueryPort paymentQueryPort,
                                            StockService stockService) {
        this.reservationRepository = reservationRepository;
        this.paymentQueryPort = paymentQueryPort;
        this.stockService = stockService;
    }

    public void recoverAll() {
        Instant threshold = Instant.now().minus(thresholdMinutes, ChronoUnit.MINUTES);
        List<StockReservation> stale = reservationRepository.findStaleReserved(threshold);
        if (stale.isEmpty()) {
            return;
        }
        Map<String, List<StockReservation>> byPaymentKey =
            stale.stream().collect(Collectors.groupingBy(StockReservation::getPaymentKey));

        for (Map.Entry<String, List<StockReservation>> entry : byPaymentKey.entrySet()) {
            recoverOne(entry.getKey(), entry.getValue());
        }
    }

    private void recoverOne(String paymentKey, List<StockReservation> reservations) {
        boolean exists;
        try {
            exists = paymentQueryPort.exists(paymentKey);
        } catch (RuntimeException e) {
            // fail-safe: 조회 실패는 release 하지 않고 skip. 다음 주기 재시도.
            log.warn("[orphan-recovery] payment 조회 실패 — skip. paymentKey={}", paymentKey, e);
            return;
        }
        if (exists) {
            // 커밋된 payment 존재 → 정상 예약, 유지.
            return;
        }
        // exists=false: 커밋 payment 없음 → orphan. release(멱등 원자 전이).
        List<StockService.ReserveItem> items = reservations.stream()
            .map(r -> new StockService.ReserveItem(r.getSkuId(), r.getQty()))
            .toList();
        stockService.release(paymentKey, items);
        log.info("[orphan-recovery] orphan 예약 release. paymentKey={} items={}", paymentKey, items.size());
    }
}
