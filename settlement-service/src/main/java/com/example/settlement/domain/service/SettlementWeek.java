package com.example.settlement.domain.service;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;

/**
 * 정산주(KST 월~일) 귀속 계산. 순수 도메인 헬퍼 — I/O 없음, 단위 테스트 가능.
 *
 * <p><b>핵심</b>: {@code cancelledAt}은 UTC({@code ...Z})이므로 KST로 변환한 <i>뒤</i>에
 * 날짜를 취해야 한다. UTC 저녁(예: 15:00Z = 다음날 00:00 KST) 이벤트가 올바른 KST 주로 귀속되게 하는
 * 이 페이즈에서 가장 실수하기 쉬운 한 줄.
 */
public final class SettlementWeek {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private SettlementWeek() {}

    /** 정산주 월요일(KST). occurredAt을 KST 날짜로 변환 후 previousOrSame(MONDAY). */
    public static LocalDate mondayOf(Instant occurredAt) {
        LocalDate kstDate = occurredAt.atZone(KST).toLocalDate();
        return kstDate.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    }

    /** 정산주 일요일(KST) = 월요일 + 6일. */
    public static LocalDate sundayOf(Instant occurredAt) {
        return mondayOf(occurredAt).plusDays(6);
    }
}
