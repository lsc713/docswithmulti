package com.example.riskmanagement.infrastructure.persistence;

import com.example.riskmanagement.application.interfaces.MerchantCancelUsageRepository;
import com.example.riskmanagement.domain.entity.MerchantCancelUsage;
import com.example.riskmanagement.domain.service.CancelLimitDomainService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("MerchantCancelUsage 원자 복원 (tryRestore, GREATEST 0 바닥)")
class MerchantCancelUsageAtomicRestoreIT extends AbstractRepositoryTest {

    @Autowired MerchantCancelUsageRepository repo;
    @Autowired CancelLimitDomainService domain;
    @Autowired TransactionTemplate tx;

    private static final LocalDate TODAY = LocalDate.of(2026, 4, 23);
    private static final AtomicLong MID = new AtomicLong(20_000); // deduct IT(10_000대)와 분리

    // ── 동치 증명: 도메인 applyCompensation(entity.restore, 0 바닥) == SQL tryRestore ──
    @ParameterizedTest(name = "used={0} restore={1} → expected used={2}")
    @CsvSource({
        "300.00, 100.00, 200.00",  // 일반 복원
        "300.00, 300.00, 0.00",    // 전액 복원 → 정확히 0
        "100.00, 300.00, 0.00",    // 초과 복원 → GREATEST 로 0 바닥 (음수 방지)
        "100.00, 0.01,   99.99",
    })
    @DisplayName("도메인 restore 와 SQL tryRestore 는 항상 같은 결과 used_amount 를 만든다")
    void domain_restore_equals_sql_tryRestore(BigDecimal used, BigDecimal restore, BigDecimal expected) {
        long m = MID.incrementAndGet();
        BigDecimal limit = new BigDecimal("5000000.00");
        repo.save(MerchantCancelUsage.reconstruct(null, m, TODAY, limit, used));

        // 도메인 규칙(스펙)을 인메모리 엔티티에 적용
        MerchantCancelUsage inMemory = MerchantCancelUsage.reconstruct(null, m, TODAY, limit, used);
        domain.applyCompensation(inMemory, restore);

        // SQL 원자 복원
        int affected = tx.execute(s -> repo.tryRestore(m, TODAY, restore));
        BigDecimal sqlUsed = repo.findByMerchantIdAndKstDate(m, TODAY).orElseThrow().getUsedAmount();

        assertThat(affected).as("대상 행 존재 → 1행 영향").isEqualTo(1);
        assertThat(inMemory.getUsedAmount()).as("도메인 규칙 결과").isEqualByComparingTo(expected);
        assertThat(sqlUsed).as("SQL == 도메인").isEqualByComparingTo(inMemory.getUsedAmount());
    }

    // ── 대상 행 없음 → 0행 (CompensateService 가 DataInconsistencyException 으로 승격) ──
    @Test
    @DisplayName("존재하지 않는 (merchant, date) 복원 → 0행 영향")
    void tryRestore_returns_zero_when_row_absent() {
        long m = MID.incrementAndGet();

        int affected = tx.execute(s -> repo.tryRestore(m, TODAY, new BigDecimal("100.00")));

        assertThat(affected).isZero();
    }

    // ── 1차 캐시 무효화 (clearAutomatically) — native UPDATE 후 재조회가 DB값을 본다 ──
    @Test
    @Transactional
    @DisplayName("native tryRestore 후 같은 TX 재조회가 stale 1차 캐시가 아닌 DB값을 본다")
    void tryRestore_invalidates_first_level_cache() {
        long m = MID.incrementAndGet();
        BigDecimal limit = new BigDecimal("1000.00");
        repo.save(MerchantCancelUsage.reconstruct(null, m, TODAY, limit, new BigDecimal("300.00")));

        BigDecimal before = repo.findByMerchantIdAndKstDate(m, TODAY).orElseThrow().getUsedAmount();
        assertThat(before).isEqualByComparingTo("300.00");

        repo.tryRestore(m, TODAY, new BigDecimal("100.00"));

        BigDecimal after = repo.findByMerchantIdAndKstDate(m, TODAY).orElseThrow().getUsedAmount();
        assertThat(after).as("stale(300)이 아니라 DB값(200)").isEqualByComparingTo("200.00");
    }
}
