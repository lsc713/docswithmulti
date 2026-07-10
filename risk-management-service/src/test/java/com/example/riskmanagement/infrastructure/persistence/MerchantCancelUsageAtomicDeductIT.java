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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("MerchantCancelUsage 원자 차감 (ensureRow + tryDeduct)")
class MerchantCancelUsageAtomicDeductIT extends AbstractRepositoryTest {

    @Autowired MerchantCancelUsageRepository repo;
    @Autowired CancelLimitDomainService domain;
    @Autowired TransactionTemplate tx;   // TX 경계는 애플리케이션 계층(repo에 @Transactional 안 박음)

    private static final LocalDate TODAY = LocalDate.of(2026, 4, 23);
    private static final AtomicLong MID = new AtomicLong(10_000); // 케이스별 유니크 merchant

    // ── #8 동시성 증명 (이번 수술의 핵심) ──
    @Test
    @DisplayName("단일 merchant 동시 차감 — 초과차감 0 AND 한도 내 전부 성공(거부 spurious 0)")
    void concurrent_deducts_never_over_and_never_spurious_reject() throws Exception {
        long m = MID.incrementAndGet();
        BigDecimal limit = new BigDecimal("300000.00");
        BigDecimal amt = new BigDecimal("10000.00");
        int capacity = 30;          // 300000 / 10000
        int threads = 50;           // 용량보다 많이 던진다
        tx.executeWithoutResult(s -> repo.ensureRow(m, TODAY, limit));

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                Integer r = tx.execute(s -> repo.tryDeduct(m, TODAY, amt)); // 각 호출 독립 TX
                if (r != null && r == 1) success.incrementAndGet();
                return null;
            }));
        }
        start.countDown();
        for (Future<?> f : futures) f.get(30, TimeUnit.SECONDS);
        pool.shutdown();

        BigDecimal used = repo.findByMerchantIdAndKstDate(m, TODAY).orElseThrow().getUsedAmount();
        assertThat(success.get()).as("한도 내면 정확히 용량만큼 성공(spurious 거부 0)").isEqualTo(capacity);
        assertThat(used).as("초과차감 0 — 정확히 상한에서 멈춤").isEqualByComparingTo(limit);
    }

    // ── #10 동치 증명: 도메인 규칙 == SQL tryDeduct(1/0) ──
    @ParameterizedTest(name = "limit={0} used={1} amt={2} → allow={3}")
    @CsvSource({
        "100.00, 0.00,   100.00, true",   // 경계: 딱 상한
        "100.00, 0.00,   100.01, false",  // 1전 초과
        "100.00, 50.00,  50.00,  true",
        "100.00, 50.00,  50.01,  false",
        "100.00, 99.99,  0.01,   true",
        "100.00, 100.00, 0.01,   false",  // 이미 소진
    })
    @DisplayName("도메인 canDeduct 와 SQL tryDeduct 는 항상 같은 결정을 낸다")
    void domain_rule_equals_sql_tryDeduct(
            BigDecimal limit, BigDecimal used, BigDecimal amt, boolean expected) {
        long m = MID.incrementAndGet();
        repo.save(MerchantCancelUsage.reconstruct(null, m, TODAY, limit, used));

        boolean domainAllows = domain.canDeduct(limit, used, amt);
        boolean sqlAllows = Boolean.TRUE.equals(tx.execute(s -> repo.tryDeduct(m, TODAY, amt) == 1));

        assertThat(domainAllows).as("도메인 규칙(스펙)").isEqualTo(expected);
        assertThat(sqlAllows).as("SQL == 도메인").isEqualTo(domainAllows);
    }

    // ── #11 1차 캐시 무효화 증명 (clearAutomatically) ──
    @Test
    @Transactional  // 한 TX/PC 안에서: managed 조회 → native 차감 → 재조회
    @DisplayName("native tryDeduct 후 같은 TX 재조회가 stale 1차 캐시가 아닌 DB값을 본다")
    void tryDeduct_invalidates_first_level_cache() {
        long m = MID.incrementAndGet();
        BigDecimal limit = new BigDecimal("1000.00");
        repo.ensureRow(m, TODAY, limit);

        // 엔티티를 managed 로 PC 에 올린다 (used=0)
        BigDecimal before = repo.findByMerchantIdAndKstDate(m, TODAY).orElseThrow().getUsedAmount();
        assertThat(before).isEqualByComparingTo("0.00");

        // native 원자 차감 (PC 우회) — clearAutomatically 없으면 아래 재조회가 stale(0) 을 봄
        repo.tryDeduct(m, TODAY, new BigDecimal("300.00"));

        BigDecimal after = repo.findByMerchantIdAndKstDate(m, TODAY).orElseThrow().getUsedAmount();
        assertThat(after).as("stale 1차 캐시(0)가 아니라 DB 값(300)").isEqualByComparingTo("300.00");
    }
}
