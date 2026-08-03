package com.example.settlement.domain;

import com.example.settlement.domain.service.SettlementWeek;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 정산주 KST 귀속 순수 단위 테스트 (CANCEL-02). 이 페이즈에서 가장 실수하기 쉬운 한 줄 —
 * UTC→KST 변환이 날짜 취득보다 <b>먼저</b> 일어나는지 명시적으로 검증.
 */
@DisplayName("SettlementWeek — KST 주 경계")
class SettlementWeekTest {

    @Test
    @DisplayName("UTC 저녁(15:00Z = 다음날 00:00 KST 월요일)은 다음 KST 주로 귀속(이전 주 아님)")
    void utcEveningBucketsIntoNextKstWeek() {
        // 2026-08-02T15:00:00Z == 2026-08-03 00:00 KST (월요일)
        Instant instant = Instant.parse("2026-08-02T15:00:00Z");

        assertThat(SettlementWeek.mondayOf(instant))
                .as("KST 변환 후 월요일 = 2026-08-03, UTC 날짜(08-02)로 계산한 이전 주 아님")
                .isEqualTo(LocalDate.of(2026, 8, 3));
        assertThat(SettlementWeek.sundayOf(instant))
                .isEqualTo(LocalDate.of(2026, 8, 9));
    }

    @Test
    @DisplayName("일요일 23:59:59.999 KST 와 다음 월요일 00:00 KST 는 서로 다른 주(period_start 7일 차)")
    void sundayNightAndMondayMorningFallInDifferentWeeks() {
        // 2026-08-02 23:59:59.999 KST == 2026-08-02T14:59:59.999Z (일요일)
        Instant sundayNight = Instant.parse("2026-08-02T14:59:59.999Z");
        // 2026-08-03 00:00:00 KST == 2026-08-02T15:00:00Z (월요일)
        Instant mondayMorning = Instant.parse("2026-08-02T15:00:00Z");

        LocalDate sundayWeek = SettlementWeek.mondayOf(sundayNight);
        LocalDate mondayWeek = SettlementWeek.mondayOf(mondayMorning);

        assertThat(sundayWeek).isEqualTo(LocalDate.of(2026, 7, 27));
        assertThat(mondayWeek).isEqualTo(LocalDate.of(2026, 8, 3));
        assertThat(sundayWeek.plusDays(7)).isEqualTo(mondayWeek);
    }
}
