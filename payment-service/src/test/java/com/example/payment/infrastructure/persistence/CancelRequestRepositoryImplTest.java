package com.example.payment.infrastructure.persistence;

import com.example.payment.domain.entity.CancelRequest;
import com.example.payment.domain.entity.CancelStatus;
import com.example.payment.fixture.CancelRequestFixture;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;

class CancelRequestRepositoryImplTest extends AbstractRepositoryTest {

    @Autowired
    private CancelRequestJpaRepository jpaRepository;

    @Test
    void should_save_and_find_by_id() {
        CancelRequest request = CancelRequestFixture.pending(1L, BigDecimal.valueOf(50000));
        CancelRequestJpaEntity entity = CancelRequestJpaEntity.from(request);

        CancelRequestJpaEntity saved = jpaRepository.save(entity);

        Optional<CancelRequestJpaEntity> found = jpaRepository.findById(saved.getId());
        assertThat(found).isPresent();
        assertThat(found.get().getRequestHash()).isEqualTo(request.getRequestHash());
        assertThat(found.get().getCancelAmount()).isEqualTo(request.getCancelAmount());
        assertThat(found.get().getStatus()).isEqualTo(CancelStatus.PENDING);
    }

    @Test
    void should_find_by_payment_id_and_request_hash() {
        Long paymentId = 1L;
        CancelRequest request = CancelRequestFixture.pending(paymentId, BigDecimal.valueOf(30000));
        jpaRepository.save(CancelRequestJpaEntity.from(request));

        Optional<CancelRequestJpaEntity> found =
            jpaRepository.findByPaymentIdAndRequestHash(paymentId, request.getRequestHash());

        assertThat(found).isPresent();
        assertThat(found.get().getPaymentId()).isEqualTo(paymentId);
    }

    @Test
    void should_find_pending_created_before_threshold() {
        CancelRequest request = CancelRequestFixture.pending(1L, BigDecimal.valueOf(50000));
        jpaRepository.save(CancelRequestJpaEntity.from(request));
        LocalDateTime threshold = LocalDateTime.now(ZoneOffset.UTC).plusSeconds(60);

        List<CancelRequestJpaEntity> found =
            jpaRepository.findByStatusAndCreatedAtBefore(CancelStatus.PENDING, threshold);

        assertThat(found).hasSize(1);
        assertThat(found.get(0).getStatus()).isEqualTo(CancelStatus.PENDING);
    }

    @Test
    void should_find_processing_updated_before_threshold() {
        CancelRequest request = CancelRequestFixture.pending(2L, BigDecimal.valueOf(30000));
        request.toProcessing();
        jpaRepository.save(CancelRequestJpaEntity.from(request));
        LocalDateTime threshold = LocalDateTime.now(ZoneOffset.UTC).plusSeconds(60);

        List<CancelRequestJpaEntity> found =
            jpaRepository.findByStatusAndUpdatedAtBefore(CancelStatus.PROCESSING, threshold);

        assertThat(found).hasSize(1);
        assertThat(found.get(0).getStatus()).isEqualTo(CancelStatus.PROCESSING);
    }

    @Test
    void should_not_find_completed_in_pending_query() {
        CancelRequest request = CancelRequestFixture.pending(3L, BigDecimal.valueOf(20000));
        request.toProcessing();
        request.toCompleted();
        jpaRepository.save(CancelRequestJpaEntity.from(request));
        LocalDateTime threshold = LocalDateTime.now(ZoneOffset.UTC).plusSeconds(60);

        List<CancelRequestJpaEntity> found =
            jpaRepository.findByStatusAndCreatedAtBefore(CancelStatus.PENDING, threshold);

        assertThat(found).isEmpty();
    }

    @Test
    void should_map_fields_correctly() {
        Long paymentId = 5L;
        BigDecimal cancelAmount = BigDecimal.valueOf(75000);

        CancelRequest request = CancelRequestFixture.pending(paymentId, cancelAmount);
        CancelRequestJpaEntity entity = CancelRequestJpaEntity.from(request);

        CancelRequestJpaEntity saved = jpaRepository.save(entity);

        Optional<CancelRequestJpaEntity> found = jpaRepository.findById(saved.getId());
        assertThat(found).isPresent();

        CancelRequestJpaEntity actual = found.get();
        assertThat(actual.getPaymentId()).isEqualTo(paymentId);
        assertThat(actual.getRequestHash()).isNotNull();
        assertThat(actual.getCancelAmount()).isEqualTo(cancelAmount);
        assertThat(actual.getStatus()).isEqualTo(CancelStatus.PENDING);
        assertThat(actual.getCreatedAt()).isNotNull();
    }

    @Test
    void should_persist_pg_transaction_key() {
        // D-01: PG(Toss) 취소 transactionKey — 감사 + 부분취소 동일금액 tiebreaker (V13)
        CancelRequest request = CancelRequestFixture.pending(7L, BigDecimal.valueOf(10000));
        request.toProcessing();
        request.assignPgTransactionKey("toss-tx-abc123");
        request.toCompleted();

        jpaRepository.save(CancelRequestJpaEntity.from(request));

        Optional<CancelRequestJpaEntity> found =
            jpaRepository.findByPaymentIdAndRequestHash(7L, request.getRequestHash());

        assertThat(found).isPresent();
        assertThat(found.get().getPgTransactionKey()).isEqualTo("toss-tx-abc123");
        assertThat(found.get().toDomain().getPgTransactionKey()).isEqualTo("toss-tx-abc123");
    }

    @Test
    void should_persist_null_idempotency_key_when_absent() {
        // V15: idempotency_key 없이 생성한 CancelRequest는 재조회 시 null이어야 한다(content-hash fallback).
        CancelRequest request = CancelRequestFixture.pending(8L, BigDecimal.valueOf(10000));

        CancelRequestJpaEntity saved = jpaRepository.save(CancelRequestJpaEntity.from(request));

        Optional<CancelRequestJpaEntity> found = jpaRepository.findById(saved.getId());
        assertThat(found).isPresent();
        assertThat(found.get().getIdempotencyKey()).isNull();
        assertThat(found.get().toDomain().getIdempotencyKey()).isNull();
    }

    @Test
    void should_persist_idempotency_key_when_present() {
        // V15: 클라 Idempotency-Key 헤더 값을 넘기면 그대로 저장/재조회되어야 한다.
        CancelRequest request = CancelRequest.create(
            9L, "hash_9", BigDecimal.valueOf(10000), "고객 변심", List.of(90L, 91L), "k1");

        CancelRequestJpaEntity saved = jpaRepository.save(CancelRequestJpaEntity.from(request));

        Optional<CancelRequestJpaEntity> found = jpaRepository.findById(saved.getId());
        assertThat(found).isPresent();
        assertThat(found.get().getIdempotencyKey()).isEqualTo("k1");
        assertThat(found.get().toDomain().getIdempotencyKey()).isEqualTo("k1");
    }
}
