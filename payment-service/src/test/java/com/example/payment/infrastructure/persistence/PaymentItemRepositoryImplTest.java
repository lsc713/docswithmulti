package com.example.payment.infrastructure.persistence;

import com.example.payment.domain.entity.PaymentItem;
import com.example.payment.domain.entity.PaymentItemStatus;
import com.example.payment.fixture.PaymentItemFixture;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

class PaymentItemRepositoryImplTest extends AbstractRepositoryTest {

    @Autowired
    private PaymentItemJpaRepository jpaRepository;

    @Test
    void should_save_and_find_all_items_by_payment_id() {
        // Given
        Long paymentId = 1L;
        PaymentItem item1 = PaymentItemFixture.activeItem(paymentId, 100L, BigDecimal.valueOf(50000));
        PaymentItem item2 = PaymentItemFixture.activeItem(paymentId, 101L, BigDecimal.valueOf(50000));

        // When
        jpaRepository.save(PaymentItemJpaEntity.from(item1));
        jpaRepository.save(PaymentItemJpaEntity.from(item2));

        // Then
        List<PaymentItemJpaEntity> found = jpaRepository.findAllByPaymentId(paymentId);
        assertThat(found).hasSize(2);
        assertThat(found).allMatch(e -> e.getPaymentId().equals(paymentId));
    }

    @Test
    void should_return_empty_list_when_payment_id_not_found() {
        // When & Then
        List<PaymentItemJpaEntity> found = jpaRepository.findAllByPaymentId(9999L);
        assertThat(found).isEmpty();
    }

    @Test
    void should_save_all_items_at_once() {
        // Given
        Long paymentId = 2L;
        PaymentItem item1 = PaymentItemFixture.activeItem(paymentId, 200L, BigDecimal.valueOf(30000));
        PaymentItem item2 = PaymentItemFixture.activeItem(paymentId, 201L, BigDecimal.valueOf(40000));
        PaymentItem item3 = PaymentItemFixture.activeItem(paymentId, 202L, BigDecimal.valueOf(30000));

        List<PaymentItemJpaEntity> entities = List.of(
            PaymentItemJpaEntity.from(item1),
            PaymentItemJpaEntity.from(item2),
            PaymentItemJpaEntity.from(item3)
        );

        // When
        jpaRepository.saveAll(entities);

        // Then
        List<PaymentItemJpaEntity> found = jpaRepository.findAllByPaymentId(paymentId);
        assertThat(found).hasSize(3);
    }

    @Test
    void should_reflect_cancelled_amount_after_partial_cancel() {
        // Given
        Long paymentId = 3L;
        PaymentItem item = PaymentItemFixture.activeItem(paymentId, 300L, BigDecimal.valueOf(100000));
        PaymentItemJpaEntity entity = PaymentItemJpaEntity.from(item);
        jpaRepository.save(entity);

        // When
        item.cancelPartially(BigDecimal.valueOf(30000));
        PaymentItemJpaEntity toUpdate = PaymentItemJpaEntity.from(item);
        toUpdate.setId(entity.getId());
        jpaRepository.save(toUpdate);

        // Then
        List<PaymentItemJpaEntity> found = jpaRepository.findAllByPaymentId(paymentId);
        assertThat(found).hasSize(1);
        assertThat(found.get(0).getCancelledAmount()).isEqualTo(BigDecimal.valueOf(30000));
        assertThat(found.get(0).getStatus()).isEqualTo(PaymentItemStatus.PARTIAL_CANCELLED);
    }

    @Test
    void should_reflect_status_when_fully_cancelled() {
        // Given
        Long paymentId = 4L;
        BigDecimal itemAmount = BigDecimal.valueOf(50000);
        PaymentItem item = PaymentItemFixture.activeItem(paymentId, 400L, itemAmount);
        PaymentItemJpaEntity entity = PaymentItemJpaEntity.from(item);
        jpaRepository.save(entity);

        // When
        item.cancelPartially(itemAmount);  // 전액 취소
        PaymentItemJpaEntity toUpdate = PaymentItemJpaEntity.from(item);
        toUpdate.setId(entity.getId());
        jpaRepository.save(toUpdate);

        // Then
        List<PaymentItemJpaEntity> found = jpaRepository.findAllByPaymentId(paymentId);
        assertThat(found).hasSize(1);
        assertThat(found.get(0).getCancelledAmount()).isEqualTo(itemAmount);
        assertThat(found.get(0).getStatus()).isEqualTo(PaymentItemStatus.CANCELLED);
    }

    @Test
    void should_map_all_fields_correctly() {
        // Given
        Long paymentId = 5L;
        Long orderItemId = 500L;
        Long productId = 1000L;
        Long productAutoId = 2000L;
        String itemName = "Test Product";
        BigDecimal itemAmount = BigDecimal.valueOf(100000);

        PaymentItem item = PaymentItem.of(paymentId, orderItemId, productId, productAutoId, itemName, itemAmount);
        PaymentItemJpaEntity entity = PaymentItemJpaEntity.from(item);

        // When
        jpaRepository.save(entity);

        // Then
        List<PaymentItemJpaEntity> found = jpaRepository.findAllByPaymentId(paymentId);
        assertThat(found).hasSize(1);

        PaymentItemJpaEntity actual = found.get(0);
        assertThat(actual.getPaymentId()).isEqualTo(paymentId);
        assertThat(actual.getOrderItemId()).isEqualTo(orderItemId);
        assertThat(actual.getProductId()).isEqualTo(productId);
        assertThat(actual.getProductAutoId()).isEqualTo(productAutoId);
        assertThat(actual.getItemName()).isEqualTo(itemName);
        assertThat(actual.getItemAmount()).isEqualTo(itemAmount);
        assertThat(actual.getCancelledAmount()).isEqualTo(BigDecimal.ZERO);
        assertThat(actual.getStatus()).isEqualTo(PaymentItemStatus.ACTIVE);
    }
}
