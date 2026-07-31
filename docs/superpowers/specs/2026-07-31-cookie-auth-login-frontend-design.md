# 쿠키 기반 인증 전환 + 로그인 데모 프론트엔드 — 설계

> 작성 2026-07-31. 기존 v2.0 인증 게이트웨이(feat/auth-gateway) 위에 쿠키 기반 세션과
> OWASP 방어를 얹고, 이를 실증하는 최소 프론트엔드(Vite+React)를 추가한다.

## 1. 목적 / 범위

- **목적**: 프론트엔드가 api-gateway를 통해 user-service 인증에 연결되는 것을 실증하되,
  토큰을 **httpOnly 쿠키**로 다루어 OWASP Top 10을 방어한다.
- **스코프(In)**: user-service 쿠키 발급 + `/me`, api-gateway CORS/CSRF/쿠키읽기,
  프론트 로그인/회원가입/로그아웃 화면.
- **Out of scope**: 결제/취소 API 연동, 소셜 로그인, 비밀번호 재설정, 자동 silent-refresh UI.
  (refresh 엔드포인트의 쿠키화는 일관성을 위해 백엔드에는 포함, 프론트 UI는 미포함.)
- **띄워야 할 백엔드**: user-service(8085) + api-gateway(8000) + user-service의 MySQL.

## 2. OWASP Top 10 방어 매핑

| 항목 | 방어 수단 | 위치 |
|------|-----------|------|
| A01 접근통제 | 쿠키 JWT 검증 통과분만 downstream, `/me`는 신뢰헤더 기반 | gateway |
| A02 암호 실패 | 쿠키 `HttpOnly`+`Secure`, HTTPS 전송, 토큰 body 비노출 | user-service |
| A03 인젝션(XSS) | httpOnly(토큰 탈취 차단) + React 이스케이프 + CSP `default-src 'self'` + `dangerouslySetInnerHTML` 금지 | 전역 |
| A05 misconfig | CORS 출처 화이트리스트(`*` 금지), 보안 응답 헤더 | gateway |
| A07 인증 실패 | 짧은 access + refresh(path 제한) + 로그아웃 시 서버측 무효화 | user-service |
| CSRF | double-submit 토큰 + SameSite + 게이트웨이 검증(상태변경 메서드) | gateway |

> SameSite 단독은 OWASP가 CSRF 단일 방어로 불충분하다고 보므로 double-submit 토큰을 더한다.

## 3. 컴포넌트 책임

### user-service
- `POST /v1/auth/signup`, `POST /v1/auth/login`: 성공 시 토큰을 **`Set-Cookie`로 발급**하고
  응답 body는 `{ "result": "OK" }`(토큰 미포함).
- `POST /v1/auth/refresh`: `refresh_token` 쿠키를 읽어 새 `access_token` 쿠키 발급(rotation).
- `POST /v1/auth/logout`: 서버측 토큰 무효화 + 쿠키 만료(`Max-Age=0`).
- `GET /v1/auth/me` (신규): 신뢰헤더 `X-User-Id` 기준 `{ userId, email, name, role }` 반환.
- 신규 유틸: `AuthCookieFactory`(쿠키 3종 생성/만료 일원화).

### api-gateway
- **CorsConfig**: 출처 화이트리스트, `Allow-Credentials: true`, preflight(OPTIONS) 처리.
- **CsrfFilter**: 상태변경 메서드(POST/PUT/PATCH/DELETE)에서 `csrf_token` 쿠키값과
  `X-CSRF-Token` 헤더값 일치 검증. 불일치/누락 → 403. 공개 로그인/회원가입/refresh는 예외.
- **JwtTrustHeaderFilter**(수정): 쿠키 `access_token`에서 JWT 우선 읽기, `Authorization: Bearer`는
  폴백 유지(기존 API 클라이언트·테스트 호환).
- **RouteConfig**(수정): `GET /v1/auth/me`(secured), `POST /v1/auth/refresh`(public) 라우트 추가/정리.

### frontend (Vite + React)
- 토큰을 **절대 저장/접근하지 않는다**. 모든 요청 `fetch(url, { credentials: 'include' })`.
- 신원 표시는 `/me` 결과로만. 로그아웃 등 상태변경은 `csrf_token` 쿠키를 읽어 `X-CSRF-Token` 헤더 첨부.
- **Vite dev proxy 사용 안 함** — 실제 cross-origin(:5173→:8000)으로 CORS를 정식 검증.
- `index.html`에 CSP meta(방어심층). 프로덕션은 서버 CSP 헤더로 승격.

## 4. 쿠키 스펙

| 쿠키 | HttpOnly | Secure | SameSite | Path | Max-Age |
|------|:--:|:--:|:--:|------|--------|
| `access_token` | ✅ | ✅ | Lax | `/` | 액세스 토큰 만료(예 15m) |
| `refresh_token` | ✅ | ✅ | Strict | `/v1/auth/refresh` | 리프레시 만료(예 14d) |
| `csrf_token` | ❌ | ✅ | Lax | `/` | 세션 수명 |

- `Secure`는 localhost에서도 Chrome이 secure context로 취급해 동작. SameSite/Secure/도메인은
  환경설정값(dev: 로컬, prod: 도메인 분리 시 SameSite=None 필요)으로 외부화.
- `csrf_token`만 non-HttpOnly(프론트가 읽어 재제출해야 하므로). 값은 인증과 무관한 난수.

## 5. 인증 플로우

```
[회원가입/로그인]
  POST /v1/auth/login {email,password}
   → gateway: CORS 허용, public 라우트 통과
   → user-service: 검증 → Set-Cookie(access, refresh, csrf), body {result:OK}
   → 브라우저: 쿠키 3종 저장

[인증 요청]
  GET /v1/auth/me  (쿠키 자동 첨부)
   → gateway: access_token 쿠키에서 JWT 검증 → 신뢰헤더(X-User-Id 등) 주입 → user-service
   → user-service: /me 프로필 반환

[상태변경 요청]
  POST /v1/auth/logout  + 헤더 X-CSRF-Token: <csrf_token 쿠키값>
   → gateway: CSRF 검증(쿠키==헤더) + JWT 검증
   → user-service: 토큰 무효화 + 쿠키 만료

[재발급]
  POST /v1/auth/refresh  (refresh_token 쿠키)
   → user-service: refresh 검증 → 새 access_token 쿠키 rotation
```

## 6. API 계약 변경 (before → after)

| 엔드포인트 | before | after |
|-----------|--------|-------|
| `POST /login`,`/signup` | body `{accessToken,refreshToken}` | `Set-Cookie` 3종 + body `{result:OK}` |
| `POST /refresh` | body in/out 토큰 | `refresh_token` 쿠키 in → `access_token` 쿠키 out |
| `POST /logout` | Bearer | 쿠키 + `X-CSRF-Token`, 응답서 쿠키 만료 |
| `GET /me` | (없음) | 신규, `{userId,email,name,role}` |

## 7. 파일 목록

```
user-service:
  presentation/controller/AuthController.java   (쿠키 발급으로 수정)
  presentation/controller/MeController.java      (신규)
  presentation/support/AuthCookieFactory.java    (신규)
  presentation/dto/MeResponse.java               (신규)
api-gateway:
  config/CorsConfig.java                         (신규)
  config/RouteConfig.java                        (/me,/refresh 라우트)
  filter/CsrfFilter.java                         (신규)
  filter/JwtTrustHeaderFilter.java               (쿠키 읽기 추가)
frontend/
  package.json, vite.config.js, index.html(CSP)
  src/main.jsx, src/App.jsx, src/api.js
```

## 8. 테스트 전략

- **user-service**: 로그인 성공 시 Set-Cookie 3종 + 속성(HttpOnly/Secure/SameSite) 검증,
  body에 토큰 미포함 검증, `/me` 신뢰헤더 매핑, 로그아웃 쿠키 만료.
- **api-gateway**: CSRF 불일치/누락 403, 정상 통과, CORS preflight 및 credentials 헤더,
  쿠키 access_token으로 JWT 검증 성공/실패, Bearer 폴백 유지.
- **프론트**: (E2E는 out) 최소 수동 확인 시나리오 문서화 — 로그인→/me 표시→로그아웃.

## 9. 환경설정 / 배포 게이트

- CORS 허용 출처, 쿠키 SameSite/Secure/Domain은 설정값. dev 기본은 `http://localhost:5173`.
- 프로덕션 도메인 분리 시 SameSite=None + Secure(HTTPS) 필수 — NetworkPolicy·JWT_SECRET 게이트는
  기존 v2.0 배포 게이트를 그대로 따른다.
