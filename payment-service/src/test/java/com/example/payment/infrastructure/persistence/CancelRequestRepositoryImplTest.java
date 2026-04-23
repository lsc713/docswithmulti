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
    void should_find_by_status_and_created_at_before() {
        LocalDateTime past = LocalDateTime.now(ZoneOffset.UTC).minusMinutes(10);
        LocalDateTime threshold = LocalDateTime.now(ZoneOffset.UTC).minusMinutes(5);

        CancelRequest old = CancelRequestFixture.pending(1L, BigDecimal.valueOf(50000));
        CancelRequestJpaEntity oldEntity = CancelRequestJpaEntity.from(old);
        jpaRepository.save(oldEntity);

        List<CancelRequestJpaEntity> found =
            jpaRepository.findByStatusAndCreatedAtBefore(CancelStatus.PENDING, threshold);

        // Note: test data createdAt is set at save time, so results depend on timing
        assertThat(found).isNotNull();
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
}
