# 프로젝트 작업 상태

> CLAUDE.md에서 분리 (2026-07-10). 진행 상황은 이 파일에서 갱신한다.
> CLAUDE.md는 매 세션 로드되므로, 드리프트 방지를 위해 변동성 높은 "상태"는 여기 둔다.

## 완료 (설계)

- [x] 전체 시스템 설계
- [x] 도메인 규칙 확정
- [x] 에러 카탈로그 확정
- [x] API 스펙 확정
- [x] Kafka 설계 확정
- [x] 전체 모듈 DDL 작성 (Flyway V1~V7)
- [x] 취소 플로우 상세 설계 (`sysdesign/cancel-design.md`)
- [x] Circuit Breaker 설계
- [x] 스케줄러 3개 설계 (pending-recovery, processing-recovery, compensation-retry)

## 구현 상태

- [x] payment-service (취소 플로우 + **OUTBOX 정식 발행**, order 주문 생성 API 소비 아님)
- [x] order-service (주문 생성 API `POST /v1/orders` + Kafka Consumer 상태 동기화)
- [x] merchant-limit-service (한도 원본 + `merchant.limit.updated` Outbox 발행)
- [x] risk-management-service (취소 검증 + 한도 소진)
- [x] product-service (재고 예약·복원 v3.0 · 카테고리 브라우징 + **SKU 가격** + **다중 이미지**(S3 presigned))
- [x] **user-service** (회원가입/로그인/JWT, v2.0 · **ADMIN 역할관리** `PATCH /v1/admin/users/{id}/role` + bootstrap 승격)
- [x] **api-gateway** (단일 진입점·JWT 검증·신뢰헤더, v2.0 · product 브라우징/이미지 + `/v1/admin/**` 라우팅)
- [x] **frontend** (Vite+React) — 상품 그리드·상세/갤러리 + ADMIN 이미지 관리(presign 업로드·삭제·순서변경) + 로그인 nav 모달

## 마일스톤 진행

```
v1  취소 코어 4서비스 (payment/order/merchant/risk)   (완료·부하실측·k3s 검증)
v2.0 인증 경계 (user-service·api-gateway·취소 인가·배포 매니페스트)  (완료, PR #77 머지)
v3.0 SKU 재고 수명주기 (product 재고 기반·결제 예약·취소 복원)      (완료, PR #78 머지)
product-catalog v1.0 (카테고리 트리·브라우징)                       (완료, PR #83 머지)
order-link v1.0 (주문↔결제 검증 링크)                               (완료, PR #84 머지)
카탈로그 프론트 (그리드·상세 + SKU 가격 + 다중 이미지 S3 presigned
  + user ADMIN 역할관리)                                           (완료, PR #88/#89/#90 · 라이브 E2E 5/5)
cancel-restore v1.0 (취소 복원 일관성 — 레그 하드닝 B2: order·product 컨슈머
  무손실·durable DLQ+알림·Redisson 재구동, 취소 코어 불변)          (완료, PR #87 머지)
어드민 콘솔 v1.0 (로그인/대시보드/상품·회원 관리, 별도 admin.html + react-router
  + `GET /v1/admin/users` 신설, 취소/스토어 불변)                   (완료)
```

## 배포 시점 남은 것 (코드는 머지, 라이브 미적용)

- v2.0: k3s NetworkPolicy(payment ingress→게이트웨이만) + JWT_SECRET 실값 주입 (없으면 인증 경계 무력)
- v3.0: product-service 배포 매니페스트(infra/k8s) + 외부 MySQL에 product_db 스키마 + Kafka `payment.cancelled` consumer(group=product-service) 배선
- 카탈로그: 실 S3(버킷 CORS) + 프론트 CSP를 실 도메인으로(현재 `localhost:9000` 하드코딩) + user `app.admin.bootstrap-emails` 실값. 프론트 라이브 E2E는 로컬 스택으로 5/5 검증(전체 스택 기동 필요)

## 후속 후보

- product 풀 카탈로그 백필(attribute·정규화 옵션·자유텍스트 검색·version) — image·category·SKU 가격은 반영됨
- 취소 복원 후속: 크로스-서비스 리컨실러(두 레그 완료 상태 대조·복구 = cancel-restore 접근 2) · 예약 시점 이동(재고 예약을 주문/체크아웃 시점 + 만료 = B1)
- M1 검증 트랙(실측 재현·무중단 하드닝·용량 개선) 재개
- CI 파이프라인(PR build+test 게이트) 부재

## 메시징 설계 (main 확정)

- `main`: payment 취소 이벤트 = **OUTBOX 정식**(TX3 원자 outbox INSERT + 커밋 후 relay). ~~TX3 인라인~~은 2026-07-29 outbox 재설계로 대체(PROJECT.md D-003).
- `variant/aftercommit`·`variant/outbox`: 초기 메시징 실험 브랜치(provenance).
