package com.example.product.application.service;

import com.example.product.application.interfaces.ProductStockRepository;
import com.example.product.application.interfaces.StockReservationRepository;
import com.example.product.common.exception.application.StockInsufficientException;
import com.example.product.domain.entity.StockReservation;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 재고 예약 (STOCK-03, D-P1-3).
 * 전-items 원자: 하나라도 부족하면 @Transactional 롤백으로 전체 실패(차감/예약 모두 원복).
 * 각 item 차감은 인프라의 원자 조건부 UPDATE(available_qty >= qty) → 오버셀 불가.
 *
 * 범위: 단일/다중아이템 happy + 부족 409. 멱등 재사용(paymentKey 중복)·release·동시성 회귀는 Plan 02.
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
            int affected = stockRepository.tryReserve(item.skuId(), item.qty());
            if (affected == 0) {
                // 부족 → 전체 롤백(이 TX에서 앞서 차감/예약한 item도 원복)
                throw new StockInsufficientException(item.skuId());
            }
            reservationRepository.save(
                    StockReservation.reserve(paymentKey, item.skuId(), item.qty()));
        }
    }
}
