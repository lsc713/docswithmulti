package com.example.product.application.service;

import com.example.product.application.interfaces.ProductStockRepository;
import com.example.product.application.interfaces.ProductSkuRepository;
import com.example.product.application.interfaces.StockReservationRepository;
import com.example.product.common.exception.application.InvalidStockReservationException;
import com.example.product.common.exception.application.StockInsufficientException;
import com.example.product.domain.entity.ProductSku;
import com.example.product.domain.entity.ReservationStatus;
import com.example.product.domain.entity.StockReservation;
import org.springframework.stereotype.Service;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.stream.Collectors;

import static com.example.product.application.interfaces.ProductStockRepository.Adjustment;

/**
 * 재고 예약/해제 (STOCK-03/04, D-P1-3·D-P1-4).
 *
 * <p><b>reserve 멱등 게이트(W1)</b>: 예약 INSERT(upsertReserved)를 차감보다 앞세운다.
 * uk_reservation_paymentkey_sku 가 winner/loser를 직렬화 → affected=1(신규 소유)만 차감,
 * affected=0(이미 예약됨)은 재사용(차감 없음). 동시 same-key도 500 없이 정확히 1회만 차감.
 *
 * <p><b>전-items 원자(D-P1-3)</b>: 단일 @Transactional — 하나라도 부족(batch affected=0)하면
 * 예외 전파로 앞서 INSERT/차감한 item까지 전부 롤백(부분 예약 없음).
 *
 * <p><b>release 원자(W2)</b>: 예약행을 일괄 잠근 뒤 RESERVED만 일괄 전이·복원한다.
 * 동시 이중 release라도 DB 행락이 직렬화 → 복원 1회(over-release 불가), 이미 RELEASED/미존재는 no-op.
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
        var adjustments = new java.util.ArrayList<Adjustment>(items.size());
        var changedProductIds = new LinkedHashSet<Long>();
        Map<Long, ProductSku> skusById = skuRepository.findAllByIdIn(
                items.stream().map(ReserveItem::skuId).distinct().toList())
            .stream()
            .collect(Collectors.toMap(ProductSku::getId, sku -> sku));
        for (ReserveItem item : items) {
            ProductSku sku = skusById.get(item.skuId());
            if (sku == null) {
                throw new InvalidStockReservationException(item.productId(), item.skuId());
            }
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
            adjustments.add(new Adjustment(item.skuId(), item.qty()));
            reserved.add(new ReservedItem(
                item.skuId(), sku.getProductId(), sku.getPrice(), item.qty()));
            changedProductIds.add(sku.getProductId());
        }
        if (!adjustments.isEmpty()) {
            adjustments.sort(java.util.Comparator.comparingLong(Adjustment::skuId));
            int[] affected = stockRepository.tryReserveAll(adjustments);
            for (int i = 0; i < affected.length; i++) {
                if (affected[i] == 0) throw new StockInsufficientException(adjustments.get(i).skuId());
            }
            if (affected.length != adjustments.size()) {
                throw new IllegalStateException("재고 일괄 차감 결과 수가 일치하지 않습니다.");
            }
        }
        publishStockChanged(changedProductIds);
        return reserved;
    }

    @Transactional
    public void release(String paymentKey, List<ReserveItem> items) {
        List<Long> requestedSkuIds = items.stream().map(ReserveItem::skuId).distinct().sorted().toList();
        if (requestedSkuIds.isEmpty()) return;

        List<StockReservation> reservations = reservationRepository
            .findAllByPaymentKeyAndSkuIdInForUpdate(paymentKey, requestedSkuIds)
            .stream()
            .filter(r -> r.getStatus() == ReservationStatus.RESERVED)
            .sorted(java.util.Comparator.comparingLong(StockReservation::getSkuId))
            .toList();
        if (reservations.isEmpty()) return;

        List<Long> reservedSkuIds = reservations.stream().map(StockReservation::getSkuId).toList();
        Map<Long, ProductSku> skusById = skuRepository.findAllByIdIn(reservedSkuIds).stream()
            .collect(Collectors.toMap(ProductSku::getId, sku -> sku));
        var changedProductIds = new LinkedHashSet<Long>();
        var adjustments = new java.util.ArrayList<Adjustment>(reservations.size());
        for (StockReservation reservation : reservations) {
            ProductSku sku = skusById.get(reservation.getSkuId());
            if (sku == null) {
                throw new InvalidStockReservationException(0, reservation.getSkuId());
            }
            adjustments.add(new Adjustment(reservation.getSkuId(), reservation.getQty()));
            changedProductIds.add(sku.getProductId());
        }

        int transitioned = reservationRepository.releaseAllReserved(paymentKey, reservedSkuIds);
        if (transitioned != reservations.size()) {
            throw new IllegalStateException("예약 일괄 해제 결과 수가 일치하지 않습니다.");
        }
        int[] restored = stockRepository.restoreAll(adjustments);
        if (restored.length != adjustments.size()) {
            throw new IllegalStateException("재고 일괄 복원 결과 수가 일치하지 않습니다.");
        }
        for (int count : restored) {
            if (count == 0) throw new IllegalStateException("복원할 재고를 찾을 수 없습니다.");
        }
        publishStockChanged(changedProductIds);
    }

    private void publishStockChanged(LinkedHashSet<Long> productIds) {
        if (!productIds.isEmpty()) eventPublisher.publishEvent(new ProductStockChangedEvent(productIds));
    }
}
