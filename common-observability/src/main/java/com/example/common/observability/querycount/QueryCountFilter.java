package com.example.common.observability.querycount;

import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;

import java.io.IOException;

/**
 * 요청 스레드의 누적 쿼리 수를 요청당 DistributionSummary로 기록한다.
 * 라우트 패턴이 없는 요청(정적/actuator/404)은 cardinality 보호를 위해 기록하지 않는다.
 */
public class QueryCountFilter extends OncePerRequestFilter {

    private final QueryCountReader reader;
    private final MeterRegistry registry;

    public QueryCountFilter(QueryCountReader reader, MeterRegistry registry) {
        this.reader = reader;
        this.registry = registry;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        reader.readAndReset(); // 요청 시작 시 잔여 카운트 제거
        try {
            chain.doFilter(request, response);
        } finally {
            long count = reader.readAndReset();
            Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
            if (pattern != null) {
                DistributionSummary.builder("db.queries.per_request")
                        .tag("uri", pattern.toString())
                        .publishPercentileHistogram()
                        .register(registry)
                        .record(count);
            }
        }
    }
}
