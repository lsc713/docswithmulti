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

- [x] payment-service (취소 플로우 + **OUTBOX 정식 발행**, order 주문 생성 API 소비 아님 · **결제 조회 GET** `GET /v1/payments`·`/{key}`(X-User-Id 소유 스코프) + **구매자 자가취소**(USER 소유자 분기, 취소 TX 코어 불변))
- [x] order-service (주문 생성 API `POST /v1/orders` + Kafka Consumer 상태 동기화 · **서버 장바구니** `/v1/cart` CRUD)
- [x] merchant-limit-service (한도 원본 + `merchant.limit.updated` Outbox 발행)
- [x] risk-management-service (취소 검증 + 한도 소진)
- [x] product-service (재고 예약·복원 v3.0 · 카테고리 브라우징 + **SKU 가격** + **다중 이미지**(S3 presigned))
- [x] **user-service** (회원가입/로그인/JWT, v2.0 · **ADMIN 역할관리** `PATCH /v1/admin/users/{id}/role` + bootstrap 승격)
- [x] **api-gateway** (단일 진입점·JWT 검증·신뢰헤더, v2.0 · product 브라우징/이미지 + `/v1/admin/**` 라우팅)
- [x] **settlement-service** (신규 정산 백엔드 v1.0 · 8086 독립 MySQL — 취소·매출 원장(가맹점×정산주 KST) + 요율 수수료/VAT/net + `payment.completed`/`payment.cancelled` 구독 + Redisson 배치 리컨실·OPEN→FINALIZED. **payment-service는 `payment.completed` 아웃박스 발행(V19) + 리컨실 조회 `GET /v1/payments/settlement`(읽기 전용) 추가 — 취소 코어 diff 0**)
- [x] **frontend** (Vite+React) — 상품 그리드·상세/갤러리 + ADMIN 이미지 관리(presign 업로드·삭제·순서변경) + 로그인 nav 모달 + **체크아웃 흐름**(바로구매·장바구니·주문내역/자가취소, 상품→주문→결제)

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
  + `GET /v1/admin/users` 신설 + `POST /v1/products` ADMIN 게이트웨이 노출·product 인가 재검증
  (GATE-01 확장), 취소/스토어 불변)                                (완료, PR #93 머지)
product-attribute v1.0 (속성/변형 정규화: 전역 속성사전·변형 조합·서술 specs)  (완료, PR #92 머지)
체크아웃 (정방향 구매 종단간, 3단계 스택 PR)
  P1 바로구매 (상품→주문→결제, skuId 노출 + 금액규약 itemAmount=단가×수량)  (완료, PR #94 머지)
  P2 서버 장바구니 (order-service cart 테이블·CRUD + 게이트웨이 라우트)      (완료, PR #98 머지, 구 #96 스택삭제로 재생성)
  P3 주문내역 + 구매자 자가취소 (결제 조회 GET + CancelAuthorizer USER 소유자 분기)  (완료, PR #97 머지)
정산 집계 코어 v1.0 (신규 settlement-service 8086, 3 phase)
  P1 취소 적재 (원장 4테이블 + payment.cancelled 구독·멱등 + KST 주별 집계)   (완료)
  P2 매출+수수료 (payment.completed 아웃박스 V19 + SALE 적재 + fee/VAT/net)   (완료)
  P3 리컨실+확정 (payment 리컨실 조회 API + Redisson 배치 → OPEN→FINALIZED)   (완료)
  전체: 취소 코어 diff 0 게이트 3회·456 tests green                        (완료, PR #95 머지 514251d)
```

## 배포 시점 남은 것 (코드는 머지, 라이브 미적용)

- v2.0: k3s NetworkPolicy(payment ingress→게이트웨이만) + JWT_SECRET 실값 주입 (없으면 인증 경계 무력). **P3 자가취소로 X-User-Id가 취소 인가에 load-bearing** — 이 NetworkPolicy 없으면 헤더 위조로 임의 결제 취소 가능
- v3.0: product-service 배포 매니페스트(infra/k8s) + 외부 MySQL에 product_db 스키마 + Kafka `payment.cancelled` consumer(group=product-service) 배선
- 어드민 콘솔: k3s NetworkPolicy(product ingress→게이트웨이만, `infra/k8s/networkpolicy/product-ingress.yaml`, payment와 동일 클래스) 배포 필수 — 없으면 `POST /v1/products` X-User-Role 스푸핑으로 ADMIN 인가 우회
- 카탈로그: 실 S3(버킷 CORS) + 프론트 CSP를 실 도메인으로(현재 `localhost:9000` 하드코딩) + user `app.admin.bootstrap-emails` 실값. 프론트 라이브 E2E는 로컬 스택으로 5/5 검증(전체 스택 기동 필요)
- 정산(settlement-service): (1) 배포 매니페스트(infra/k8s) + 외부 MySQL에 `settlement_db` 스키마(Flyway V1) (2) Kafka `payment.completed`·`payment.cancelled` consumer(group=settlement-service) 배선 (3) **Redis(Redisson) 배선** — 배치 리컨실 스케줄러 분산락 의존(REDIS_HOST/PORT, product/order와 동일 클래스) (4) **`GET /v1/payments/settlement`는 호출자 인가 없음(교차가맹점 재무 조회)** → k3s NetworkPolicy로 payment ingress를 게이트웨이 파드로만 제한 필수(없으면 헤더 스푸핑으로 임의 가맹점 정산액 열람, payment/product와 동일 클래스). fee/vat/net은 리컨실 FINALIZE 시 영속 — 요율 미설정 가맹점은 확정 보류(config 선주입 필요)

## 후속 후보

- product 풀 카탈로그 백필(자유텍스트 검색·version) — image·category·SKU 가격·**attribute/변형 정규화(#92)**는 반영됨
- 취소 복원 후속: 크로스-서비스 리컨실러(두 레그 완료 상태 대조·복구 = cancel-restore 접근 2) · 예약 시점 이동(재고 예약을 주문/체크아웃 시점 + 만료 = B1)
- 정산 v2: 지급 실행(payout·은행이체·FINALIZED→PAID 상태머신) · 요율 차등/이력(effective-dated) · 정산 명세서(statement) 발급 · 취소 수수료 환급(현재는 취소 거래액만 net 차감) · 주기 다양화(일/월별) · 조정·정정(adjustment) 원장 · 가맹점 정산계좌 관리
- M1 검증 트랙(실측 재현·무중단 하드닝·용량 개선) 재개
- CI 파이프라인(PR build+test 게이트) 부재

## 메시징 설계 (main 확정)

- `main`: payment 취소 이벤트 = **OUTBOX 정식**(TX3 원자 outbox INSERT + 커밋 후 relay). ~~TX3 인라인~~은 2026-07-29 outbox 재설계로 대체(PROJECT.md D-003).
- `variant/aftercommit`·`variant/outbox`: 초기 메시징 실험 브랜치(provenance).
