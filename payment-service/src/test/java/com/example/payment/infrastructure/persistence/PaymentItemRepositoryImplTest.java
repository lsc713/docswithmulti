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
        Long paymentId = 1L;
        PaymentItem item1 = PaymentItemFixture.active(paymentId, 100L, BigDecimal.valueOf(50000));
        PaymentItem item2 = PaymentItemFixture.active(paymentId, 101L, BigDecimal.valueOf(50000));

        jpaRepository.save(PaymentItemJpaEntity.from(item1));
        jpaRepository.save(PaymentItemJpaEntity.from(item2));

        List<PaymentItemJpaEntity> found = jpaRepository.findAllByPaymentIdOrderByIdAsc(paymentId);
        assertThat(found).hasSize(2);
        assertThat(found).allMatch(e -> e.getPaymentId().equals(paymentId));
    }

    @Test
    void should_return_empty_list_when_payment_id_not_found() {
        List<PaymentItemJpaEntity> found = jpaRepository.findAllByPaymentIdOrderByIdAsc(9999L);
        assertThat(found).isEmpty();
    }

    @Test
    void should_save_all_items_at_once() {
        Long paymentId = 2L;
        PaymentItem item1 = PaymentItemFixture.active(paymentId, 200L, BigDecimal.valueOf(30000));
        PaymentItem item2 = PaymentItemFixture.active(paymentId, 201L, BigDecimal.valueOf(40000));
        PaymentItem item3 = PaymentItemFixture.active(paymentId, 202L, BigDecimal.valueOf(30000));

        jpaRepository.saveAll(List.of(
            PaymentItemJpaEntity.from(item1),
            PaymentItemJpaEntity.from(item2),
            PaymentItemJpaEntity.from(item3)
        ));

        List<PaymentItemJpaEntity> found = jpaRepository.findAllByPaymentIdOrderByIdAsc(paymentId);
        assertThat(found).hasSize(3);
    }

    @Test
    void should_reflect_status_when_fully_cancelled() {
        Long paymentId = 3L;
        BigDecimal itemAmount = BigDecimal.valueOf(50000);
        PaymentItem item = PaymentItemFixture.active(paymentId, 400L, itemAmount);
        PaymentItemJpaEntity entity = PaymentItemJpaEntity.from(item);
        jpaRepository.save(entity);

        item.cancel();
        PaymentItemJpaEntity toUpdate = PaymentItemJpaEntity.from(item);
        toUpdate.setId(entity.getId());
        jpaRepository.save(toUpdate);

        List<PaymentItemJpaEntity> found = jpaRepository.findAllByPaymentIdOrderByIdAsc(paymentId);
        assertThat(found).hasSize(1);
        assertThat(found.get(0).getStatus()).isEqualTo(PaymentItemStatus.CANCELLED);
    }

    @Test
    void should_map_all_fields_correctly() {
        Long paymentId = 5L;
        Long orderItemId = 500L;
        Long productId = 1000L;
        Long productAutoId = 2000L;
        String itemName = "Test Product";
        BigDecimal itemAmount = BigDecimal.valueOf(100000);

        PaymentItem item = PaymentItem.of(paymentId, orderItemId, productId, productAutoId, itemName, itemAmount);
        jpaRepository.save(PaymentItemJpaEntity.from(item));

        List<PaymentItemJpaEntity> found = jpaRepository.findAllByPaymentIdOrderByIdAsc(paymentId);
        assertThat(found).hasSize(1);

        PaymentItemJpaEntity actual = found.get(0);
        assertThat(actual.getPaymentId()).isEqualTo(paymentId);
        assertThat(actual.getOrderItemId()).isEqualTo(orderItemId);
        assertThat(actual.getStatus()).isEqualTo(PaymentItemStatus.ACTIVE);
    }
}
