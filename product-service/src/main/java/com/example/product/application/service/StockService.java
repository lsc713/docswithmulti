package com.example.product.application.service;

import com.example.product.application.interfaces.ProductStockRepository;
import com.example.product.application.interfaces.ProductSkuRepository;
import com.example.product.application.interfaces.StockReservationRepository;
import com.example.product.common.exception.application.InvalidStockReservationException;
import com.example.product.common.exception.application.StockInsufficientException;
import com.example.product.domain.entity.ProductSku;
import com.example.product.domain.entity.StockReservation;
import org.springframework.stereotype.Service;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.LinkedHashSet;

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
    private final ProductSkuRepository skuRepository;
    private final ApplicationEventPublisher eventPublisher;

    public StockService(ProductStockRepository stockRepository,
                        StockReservationRepository reservationRepository,
                        ProductSkuRepository skuRepository,
                        ApplicationEventPublisher eventPublisher) {
        this.stockRepository = stockRepository;
        this.reservationRepository = reservationRepository;
        this.skuRepository = skuRepository;
        this.eventPublisher = eventPublisher;
    }

    public record ReserveItem(long productId, long skuId, int qty) {
        public ReserveItem(long skuId, int qty) {
            this(0, skuId, qty);
        }
    }

    public record ReservedItem(long skuId, long productId, long unitPrice, int quantity) {}

    @Transactional
    public List<ReservedItem> reserve(String paymentKey, List<ReserveItem> items) {
        var reserved = new java.util.ArrayList<ReservedItem>(items.size());
        var changedProductIds = new LinkedHashSet<Long>();
        for (ReserveItem item : items) {
            ProductSku sku = skuRepository.findById(item.skuId())
                .orElseThrow(() -> new InvalidStockReservationException(item.productId(), item.skuId()));
            if (item.productId() > 0 && sku.getProductId() != item.productId()) {
                throw new InvalidStockReservationException(item.productId(), item.skuId());
            }

            // W1: 예약 INSERT를 차감보다 앞세운 멱등 게이트. affected=0 → 이미 소유됨(loser) → 재사용, 차감 생략.
            int inserted = reservationRepository.upsertReserved(
                paymentKey, item.skuId(), item.qty(), sku.getPrice());
            if (inserted == 0) {
                StockReservation existing = reservationRepository
                    .findByPaymentKeyAndSkuIdForUpdate(paymentKey, item.skuId())
                    .orElseThrow(() -> new IllegalStateException("예약 멱등 행을 찾을 수 없습니다."));
                if (existing.getQty() != item.qty()) {
                    throw new InvalidStockReservationException("같은 예약 키의 수량이 다릅니다.");
                }
                reserved.add(new ReservedItem(
                    item.skuId(), sku.getProductId(), existing.getUnitPrice(), existing.getQty()));
                continue;
            }
            // inserted=1: 이 요청이 예약 소유 → 원자 조건부 차감
            int affected = stockRepository.tryReserve(item.skuId(), item.qty());
            if (affected == 0) {
                // 부족 → 전체 롤백(방금 INSERT한 예약행 + 앞선 item 차감/예약 모두 원복)
                throw new StockInsufficientException(item.skuId());
            }
            reserved.add(new ReservedItem(
                item.skuId(), sku.getProductId(), sku.getPrice(), item.qty()));
            changedProductIds.add(sku.getProductId());
        }
        publishStockChanged(changedProductIds);
        return reserved;
    }

    @Transactional
    public void release(String paymentKey, List<ReserveItem> items) {
        var changedProductIds = new LinkedHashSet<Long>();
        for (ReserveItem item : items) {
            // W2: 조건부 상태전이가 재고 복원의 유일 트리거 → affected=1일 때만 복원(over-release 불가).
            int transitioned = reservationRepository.releaseIfReserved(paymentKey, item.skuId());
            if (transitioned == 1) {
                stockRepository.restore(item.skuId(), item.qty());
                changedProductIds.add(skuRepository.findById(item.skuId())
                        .orElseThrow(() -> new InvalidStockReservationException(item.productId(), item.skuId()))
                        .getProductId());
            }
            // transitioned=0: 이미 RELEASED/미존재 → no-op
        }
        publishStockChanged(changedProductIds);
    }

    private void publishStockChanged(LinkedHashSet<Long> productIds) {
        if (!productIds.isEmpty()) eventPublisher.publishEvent(new ProductStockChangedEvent(productIds));
    }
}
