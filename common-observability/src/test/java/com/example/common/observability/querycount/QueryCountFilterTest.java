package com.example.common.observability.querycount;

import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.HandlerMapping;

import static org.assertj.core.api.Assertions.assertThat;

class QueryCountFilterTest {

    /** readAndReset()가 [시작=0, 종료=N] 순으로 값을 내는 가짜 리더. */
    static class FakeReader implements QueryCountReader {
        private final long[] values;
        private int idx = 0;
        FakeReader(long... values) { this.values = values; }
        @Override public long readAndReset() { return values[idx++]; }
    }

    @Test
    void recordsQueryCountWithRoutePatternTag() throws Exception {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        QueryCountFilter filter = new QueryCountFilter(new FakeReader(0, 7), registry, "payment");

        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/payments/1/cancel");
        req.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/payments/{id}/cancel");
        MockHttpServletResponse res = new MockHttpServletResponse();

        filter.doFilter(req, res, (rq, rs) -> {});

        DistributionSummary ds = registry.get("db.queries.per_request")
                .tag("service", "payment")
                .tag("uri", "/payments/{id}/cancel")
                .summary();
        assertThat(ds.count()).isEqualTo(1L);
        assertThat(ds.totalAmount()).isEqualTo(7.0);
    }

    @Test
    void unmappedRequestNotRecorded() throws Exception {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        QueryCountFilter filter = new QueryCountFilter(new FakeReader(0, 3), registry, "payment");

        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/actuator/prometheus");
        // BEST_MATCHING_PATTERN_ATTRIBUTE 미설정 → 라우트 패턴 없음
        MockHttpServletResponse res = new MockHttpServletResponse();

        filter.doFilter(req, res, (rq, rs) -> {});

        assertThat(registry.find("db.queries.per_request").summary()).isNull();
    }
}
