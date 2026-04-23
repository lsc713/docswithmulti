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
        // Given
        CancelRequest request = CancelRequestFixture.pendingCancelRequest();
        CancelRequestJpaEntity entity = CancelRequestJpaEntity.from(request);

        // When
        CancelRequestJpaEntity saved = jpaRepository.save(entity);

        // Then
        Optional<CancelRequestJpaEntity> found = jpaRepository.findById(saved.getId());
        assertThat(found).isPresent();
        assertThat(found.get().getIdempotencyKey()).isEqualTo(request.getIdempotencyKey());
        assertThat(found.get().getCancelAmount()).isEqualTo(request.getCancelAmount());
        assertThat(found.get().getStatus()).isEqualTo(CancelStatus.PENDING);
    }

    @Test
    void should_find_all_by_payment_id() {
        // Given
        Long paymentId = 1L;
        CancelRequest request1 = CancelRequestFixture.cancelRequest(
            paymentId, BigDecimal.valueOf(30000), "idem_001"
        );
        CancelRequest request2 = CancelRequestFixture.cancelRequest(
            paymentId, BigDecimal.valueOf(20000), "idem_002"
        );

        // When
        jpaRepository.save(CancelRequestJpaEntity.from(request1));
        jpaRepository.save(CancelRequestJpaEntity.from(request2));

        // Then
        List<CancelRequestJpaEntity> found = jpaRepository.findAllByPaymentId(paymentId);
        assertThat(found).hasSize(2);
        assertThat(found).allMatch(e -> e.getPaymentId().equals(paymentId));
    }

    @Test
    void should_find_only_completed_by_payment_id() {
        // Given
        Long paymentId = 2L;
        CancelRequest pendingRequest = CancelRequestFixture.cancelRequest(
            paymentId, BigDecimal.valueOf(10000), "idem_pending"
        );
        CancelRequest completedRequest = CancelRequestFixture.cancelRequest(
            paymentId, BigDecimal.valueOf(20000), "idem_completed"
        );

        CancelRequestJpaEntity pendingEntity = CancelRequestJpaEntity.from(pendingRequest);
        jpaRepository.save(pendingEntity);

        // 다른 요청은 COMPLETED로 변경
        CancelRequestJpaEntity completedEntity = CancelRequestJpaEntity.from(completedRequest);
        completedEntity.setStatus(CancelStatus.COMPLETED);
        completedEntity.setCompletedAt(LocalDateTime.now(ZoneOffset.UTC));
        jpaRepository.save(completedEntity);

        // When
        List<CancelRequestJpaEntity> found = jpaRepository.findCompletedByPaymentId(paymentId);

        // Then
        assertThat(found).hasSize(1);
        assertThat(found.get(0).getStatus()).isEqualTo(CancelStatus.COMPLETED);
        assertThat(found.get(0).getIdempotencyKey()).isEqualTo("idem_completed");
    }

    @Test
    void should_find_stuck_processing_requests() {
        // Given
        LocalDateTime past = LocalDateTime.now(ZoneOffset.UTC).minusMinutes(10);
        LocalDateTime recent = LocalDateTime.now(ZoneOffset.UTC).minusMinutes(1);

        // 10분 전부터 PROCESSING 상태
        CancelRequest stuckRequest = CancelRequestFixture.cancelRequest(
            1L, BigDecimal.valueOf(50000), "idem_stuck"
        );
        CancelRequestJpaEntity stuckEntity = CancelRequestJpaEntity.from(stuckRequest);
        stuckEntity.setStatus(CancelStatus.PROCESSING);
        stuckEntity.setProcessingStartedAt(past);
        jpaRepository.save(stuckEntity);

        // 1분 전부터 PROCESSING 상태 (최근)
        CancelRequest recentRequest = CancelRequestFixture.cancelRequest(
            2L, BigDecimal.valueOf(30000), "idem_recent"
        );
        CancelRequestJpaEntity recentEntity = CancelRequestJpaEntity.from(recentRequest);
        recentEntity.setStatus(CancelStatus.PROCESSING);
        recentEntity.setProcessingStartedAt(recent);
        jpaRepository.save(recentEntity);

        // When - 5분 이상 PROCESSING 중인 건 찾기
        LocalDateTime threshold = LocalDateTime.now(ZoneOffset.UTC).minusMinutes(5);
        List<CancelRequestJpaEntity> found = jpaRepository.findStuckProcessingRequests(threshold);

        // Then
        assertThat(found).hasSize(1);
        assertThat(found.get(0).getIdempotencyKey()).isEqualTo("idem_stuck");
    }

    @Test
    void should_not_include_non_processing_status_in_stuck_requests() {
        // Given
        LocalDateTime past = LocalDateTime.now(ZoneOffset.UTC).minusMinutes(10);

        // COMPLETED 상태 (오래됨)
        CancelRequest completedRequest = CancelRequestFixture.cancelRequest(
            3L, BigDecimal.valueOf(10000), "idem_completed_old"
        );
        CancelRequestJpaEntity completedEntity = CancelRequestJpaEntity.from(completedRequest);
        completedEntity.setStatus(CancelStatus.COMPLETED);
        completedEntity.setProcessingStartedAt(past);
        completedEntity.setCompletedAt(past);
        jpaRepository.save(completedEntity);

        // FAILED 상태 (오래됨)
        CancelRequest failedRequest = CancelRequestFixture.cancelRequest(
            4L, BigDecimal.valueOf(20000), "idem_failed_old"
        );
        CancelRequestJpaEntity failedEntity = CancelRequestJpaEntity.from(failedRequest);
        failedEntity.setStatus(CancelStatus.FAILED);
        failedEntity.setProcessingStartedAt(past);
        failedEntity.setFailedReason("Limit exceeded");
        jpaRepository.save(failedEntity);

        // When
        LocalDateTime threshold = LocalDateTime.now(ZoneOffset.UTC).minusMinutes(5);
        List<CancelRequestJpaEntity> found = jpaRepository.findStuckProcessingRequests(threshold);

        // Then
        assertThat(found).isEmpty();  // COMPLETED, FAILED는 제외
    }

    @Test
    void should_map_all_fields_correctly() {
        // Given
        Long paymentId = 5L;
        String idempotencyKey = "idem_test_mapping";
        BigDecimal cancelAmount = BigDecimal.valueOf(75000);
        String cancelReason = "Test cancel";

        CancelRequest request = CancelRequestFixture.cancelRequest(
            paymentId, cancelAmount, idempotencyKey
        );
        CancelRequestJpaEntity entity = CancelRequestJpaEntity.from(request);

        // When
        CancelRequestJpaEntity saved = jpaRepository.save(entity);

        // Then
        Optional<CancelRequestJpaEntity> found = jpaRepository.findById(saved.getId());
        assertThat(found).isPresent();

        CancelRequestJpaEntity actual = found.get();
        assertThat(actual.getPaymentId()).isEqualTo(paymentId);
        assertThat(actual.getIdempotencyKey()).isEqualTo(idempotencyKey);
        assertThat(actual.getCancelAmount()).isEqualTo(cancelAmount);
        assertThat(actual.getStatus()).isEqualTo(CancelStatus.PENDING);
        assertThat(actual.getCreatedAt()).isNotNull();
    }
}
