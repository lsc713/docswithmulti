package com.example.riskmanagement.infrastructure.persistence;

import com.example.riskmanagement.domain.entity.MerchantCancelUsage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.*;

class MerchantCancelUsageRepositoryImplTest extends AbstractRepositoryTest {

    @Autowired MerchantCancelUsageJpaRepository jpaRepository;

    private static final LocalDate TODAY = LocalDate.of(2026, 4, 23);

    @Test
    @DisplayName("저장 후 merchantId+kstDate로 조회")
    void save_and_find_by_merchant_id_and_kst_date() {
        MerchantCancelUsageJpaEntity entity = MerchantCancelUsageJpaEntity.from(
            MerchantCancelUsage.create(1L, TODAY, BigDecimal.valueOf(5_000_000)));
        jpaRepository.save(entity);

        var found = jpaRepository.findByMerchantIdAndKstDate(1L, TODAY);
        assertThat(found).isPresent();
        assertThat(found.get().toDomain().getDailyLimit())
            .isEqualByComparingTo(BigDecimal.valueOf(5_000_000));
    }

    @Test
    @DisplayName("동일 merchantId+kstDate 중복 저장 시 DataIntegrityViolationException")
    void duplicate_merchant_id_kst_date_throws() {
        jpaRepository.save(MerchantCancelUsageJpaEntity.from(
            MerchantCancelUsage.create(2L, TODAY, BigDecimal.valueOf(5_000_000))));

        assertThatThrownBy(() -> jpaRepository.saveAndFlush(
            MerchantCancelUsageJpaEntity.from(
                MerchantCancelUsage.create(2L, TODAY, BigDecimal.valueOf(3_000_000)))))
            .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }
}
