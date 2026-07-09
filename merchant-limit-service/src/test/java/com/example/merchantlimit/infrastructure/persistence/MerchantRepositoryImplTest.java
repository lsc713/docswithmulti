package com.example.merchantlimit.infrastructure.persistence;

import com.example.merchantlimit.fixture.MerchantFixture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.*;

class MerchantRepositoryImplTest extends AbstractRepositoryTest {

    @Autowired MerchantJpaRepository jpaRepository;

    @Test
    @DisplayName("가맹점 저장 후 merchantKey로 조회")
    void save_and_find_by_merchant_key() {
        jpaRepository.save(MerchantJpaEntity.from(MerchantFixture.newMerchant()));

        var found = jpaRepository.findByMerchantKey("mct_001");
        assertThat(found).isPresent();
        assertThat(found.get().getMerchantKey()).isEqualTo("mct_001");
    }

    @Test
    @DisplayName("merchantKey 중복 저장 시 DataIntegrityViolationException")
    void duplicate_merchant_key_throws() {
        jpaRepository.save(MerchantJpaEntity.from(MerchantFixture.newMerchant()));

        assertThatThrownBy(() ->
            jpaRepository.saveAndFlush(MerchantJpaEntity.from(MerchantFixture.newMerchant())))
            .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("존재하지 않는 merchantKey 조회 시 empty")
    void find_by_unknown_merchant_key_returns_empty() {
        assertThat(jpaRepository.findByMerchantKey("unknown")).isEmpty();
    }
}
