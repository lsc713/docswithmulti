package com.example.product.application.service;

import com.example.product.application.interfaces.ProductStockRepository;
import com.example.product.application.interfaces.StockReservationRepository;
import com.example.product.common.exception.application.StockInsufficientException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 재고 예약/해제 (STOCK-03/04, D-P1-3·D-P1-4).
 *
 * <p><b>reserve 멱등 게이트(W1)</b>: 예약 INSERT(upsertReserved)를 차감보다 앞세운다.
 * uk_reservation_paymentkey_sku 가 winner/loser를 직렬화 → affected=1(신규 소유)만 차감,
 * affected=0(이미 예약됨)은 재사용(차감 없음). 동시 same-key도 500 없이 정확히 1회만 차감.
 *
 * <p><b>전-items 원자(D-P1-3)</b>: 단일 @Transactional — 하나라도 부족(tryReserve=0)하면
 * 예외 전파로 앞서 INSERT/차감한 item까지 전부 롤백(부분 예약 없음).
 *
 * <p><b>release 원자(W2)</b>: releaseIfReserved(조건부 상태전이) affected=1일 때만 재고 복원.
 * 동시 이중 release라도 DB가 전이를 직렬화 → 복원 1회(over-release 불가), 이미 RELEASED/미존재는 no-op.
 */
@Service
public class StockService {

    private final ProductStockRepository stockRepository;
    private final StockReservationRepository reservationRepository;

    public StockService(ProductStockRepository stockRepository,
                        StockReservationRepository reservationRepository) {
        this.stockRepository = stockRepository;
        this.reservationRepository = reservationRepository;
    }

    public record ReserveItem(long skuId, int qty) {}

    @Transactional
    public void reserve(String paymentKey, List<ReserveItem> items) {
        for (ReserveItem item : items) {
            // W1: 예약 INSERT를 차감보다 앞세운 멱등 게이트. affected=0 → 이미 소유됨(loser) → 재사용, 차감 생략.
            int inserted = reservationRepository.upsertReserved(paymentKey, item.skuId(), item.qty());
            if (inserted == 0) {
                continue; // 멱등 재사용 — 중복차감/오버셀 불가
            }
            // inserted=1: 이 요청이 예약 소유 → 원자 조건부 차감
            int affected = stockRepository.tryReserve(item.skuId(), item.qty());
            if (affected == 0) {
                // 부족 → 전체 롤백(방금 INSERT한 예약행 + 앞선 item 차감/예약 모두 원복)
                throw new StockInsufficientException(item.skuId());
            }
        }
    }

    @Transactional
    public void release(String paymentKey, List<ReserveItem> items) {
        for (ReserveItem item : items) {
            // W2: 조건부 상태전이가 재고 복원의 유일 트리거 → affected=1일 때만 복원(over-release 불가).
            int transitioned = reservationRepository.releaseIfReserved(paymentKey, item.skuId());
            if (transitioned == 1) {
                stockRepository.restore(item.skuId(), item.qty());
            }
            // transitioned=0: 이미 RELEASED/미존재 → no-op
        }
    }
}
