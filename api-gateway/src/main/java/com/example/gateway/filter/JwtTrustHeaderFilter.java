package com.example.gateway.filter;

import com.example.gateway.config.JwtVerifier;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.function.HandlerFilterFunction;
import org.springframework.web.servlet.function.HandlerFunction;
import org.springframework.web.servlet.function.ServerRequest;
import org.springframework.web.servlet.function.ServerResponse;

import java.util.Map;

/**
 * 게이트웨이 JWT 게이트 (D-P2-2/D-P2-3, GATE-02/03). Spring Security 없이 단일
 * HandlerFilterFunction으로 3책임 응집: strip → verify → 401 단락 or 신뢰 헤더 주입.
 *
 * <p>신뢰 헤더 계약(one-way, Phase 3 payment 소비): X-User-Id / X-User-Role / X-Merchant-Id.
 * JWT claim(subject=userId, role, merchantId)과 1:1 대응.
 */
@Component
public class JwtTrustHeaderFilter implements HandlerFilterFunction<ServerResponse, ServerResponse> {

    public static final String H_USER_ID = "X-User-Id";
    public static final String H_USER_ROLE = "X-User-Role";
    public static final String H_MERCHANT_ID = "X-Merchant-Id";

    private final JwtVerifier verifier;

    public JwtTrustHeaderFilter(JwtVerifier verifier) {
        this.verifier = verifier;
    }

    @Override
    public ServerResponse filter(ServerRequest request, HandlerFunction<ServerResponse> next) throws Exception {
        // 1. strip: 클라가 위조 전송한 신뢰 헤더 무조건 제거 (스푸핑 방지, 비협상 — T-02-01)
        ServerRequest.Builder mutated = ServerRequest.from(request)
                .headers(h -> {
                    h.remove(H_USER_ID);
                    h.remove(H_USER_ROLE);
                    h.remove(H_MERCHANT_ID);
                });

        // 2. Bearer 추출 — 없음/형식오류 → 401 단락 (GATE-03, next 미호출)
        String auth = request.headers().firstHeader(HttpHeaders.AUTHORIZATION);
        if (auth == null || !auth.startsWith("Bearer ")) {
            return unauthorized("TOKEN_MISSING");
        }

        // 3. 서명·만료 검증 — 실패 → 401 단락 (GATE-03)
        Claims claims;
        try {
            claims = verifier.parse(auth.substring(7));
        } catch (ExpiredJwtException e) {
            return unauthorized("TOKEN_EXPIRED");
        } catch (JwtException | IllegalArgumentException e) {
            return unauthorized("TOKEN_INVALID");
        }

        // 4. 검증 성공 → 게이트웨이 신뢰 헤더 재설정 후 프록시 (GATE-02)
        mutated.header(H_USER_ID, claims.getSubject());
        String role = claims.get("role", String.class);
        if (role != null) {
            mutated.header(H_USER_ROLE, role);
        }
        Object merchantId = claims.get("merchantId");
        if (merchantId != null) {
            mutated.header(H_MERCHANT_ID, String.valueOf(merchantId));
        }

        return next.handle(mutated.build());
    }

    private ServerResponse unauthorized(String code) {
        // user-service GlobalExceptionHandler envelope와 동일 {code,message} (D-P2-7)
        return ServerResponse.status(HttpStatus.UNAUTHORIZED)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("code", code, "message", "인증 실패"));
    }
}
