package com.example.payment.application.service;

import com.example.payment.application.usecase.PaymentSettlementQuery;
import com.example.payment.domain.entity.PaymentStatus;
import com.example.payment.infrastructure.persistence.PaymentJpaEntity;
import com.example.payment.infrastructure.persistence.PaymentSettlementJpaRepository;
import com.example.payment.infrastructure.persistence.PaymentSettlementJpaRepository.CancelRow;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * RECON-03 정산 대사 조회 서비스. read-only — SALE(created_at)·CANCEL(completed_at) 두 윈도우를
 * 독립 조회한 뒤 paymentKey로 조립한다. 어떤 write/mutation도 하지 않는다 (@Transactional(readOnly)).
 *
 * created_at은 UTC LocalDateTime(V1)이므로 from/to Instant를 UTC LocalDateTime으로 변환해 질의하고,
 * 응답 시각은 다시 UTC Instant로 되돌린다.
 */
@Service
@RequiredArgsConstructor
public class PaymentSettlementQueryService implements PaymentSettlementQuery {

    private final PaymentSettlementJpaRepository repository;

    @Override
    @Transactional(readOnly = true)
    public List<PaymentSettlementView> query(long merchantId, Instant from, Instant to) {
        LocalDateTime fromLdt = LocalDateTime.ofInstant(from, ZoneOffset.UTC);
        LocalDateTime toLdt = LocalDateTime.ofInstant(to, ZoneOffset.UTC);

        // SALE 윈도우로 시드 (비-PENDING 결제만 — 모든 non-PENDING은 실제 판매)
        Map<String, Acc> byKey = new LinkedHashMap<>();
        for (PaymentJpaEntity p : repository
                .findByMerchantIdAndCreatedAtGreaterThanEqualAndCreatedAtLessThan(merchantId, fromLdt, toLdt)) {
            if (p.getStatus() == PaymentStatus.PENDING) {
                continue;
            }
            byKey.put(p.getPaymentKey(), Acc.ofSale(p));
        }

        // CANCEL 윈도우를 접어 넣음 — 부모가 SALE 윈도우 밖이면 carrier 엔트리 생성
        for (CancelRow c : repository.findCompletedCancelsInWindow(merchantId, fromLdt, toLdt)) {
            Acc acc = byKey.computeIfAbsent(c.getPaymentKey(), k -> Acc.ofCarrier(c));
            acc.cancels.add(new CancelView(
                c.getCancelRequestId(),
                c.getCancelAmount(),
                c.getCompletedAt().toInstant(ZoneOffset.UTC)));
        }

        return byKey.values().stream().map(Acc::toView).toList();
    }

    /** 조립용 가변 누적자. */
    private static final class Acc {
        private String paymentKey;
        private long merchantId;
        private java.math.BigDecimal totalAmount;
        private String status;
        private Instant createdAt;
        private final List<CancelView> cancels = new ArrayList<>();

        static Acc ofSale(PaymentJpaEntity p) {
            Acc a = new Acc();
            a.paymentKey = p.getPaymentKey();
            a.merchantId = p.getMerchantId();
            a.totalAmount = p.getTotalAmount();
            a.status = p.getStatus().name();
            a.createdAt = p.getCreatedAt().toInstant(ZoneOffset.UTC);
            return a;
        }

        static Acc ofCarrier(CancelRow c) {
            Acc a = new Acc();
            a.paymentKey = c.getPaymentKey();
            a.merchantId = c.getMerchantId();
            a.totalAmount = c.getTotalAmount();
            a.status = c.getStatus();
            a.createdAt = c.getCreatedAt().toInstant(ZoneOffset.UTC);
            return a;
        }

        PaymentSettlementView toView() {
            return new PaymentSettlementView(paymentKey, merchantId, totalAmount, status, createdAt,
                List.copyOf(cancels));
        }
    }
}
