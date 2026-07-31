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
- [x] product-service (상품/SKU + 재고 예약·복원 수명주기, v3.0 최소 카탈로그)
- [x] **user-service** (회원가입/로그인/JWT, v2.0)
- [x] **api-gateway** (단일 진입점·JWT 검증·신뢰헤더, v2.0)

## 마일스톤 진행

```
v1  취소 코어 4서비스 (payment/order/merchant/risk)   (완료·부하실측·k3s 검증)
v2.0 인증 경계 (user-service·api-gateway·취소 인가·배포 매니페스트)  (완료, PR #77 머지)
v3.0 SKU 재고 수명주기 (product 재고 기반·결제 예약·취소 복원)      (완료, PR #78 머지)
```

## 배포 시점 남은 것 (코드는 머지, 라이브 미적용)

- v2.0: k3s NetworkPolicy(payment ingress→게이트웨이만) + JWT_SECRET 실값 주입 (없으면 인증 경계 무력)
- v3.0: product-service 배포 매니페스트(infra/k8s) + 외부 MySQL에 product_db 스키마 + Kafka `payment.cancelled` consumer(group=product-service) 배선

## 후속 후보

- product 풀 카탈로그 백필(category 대중소·attribute·image·version) — 경로 Y 후속 서브프로젝트
- M1 검증 트랙(실측 재현·무중단 하드닝·용량 개선) 재개
- CI 파이프라인(PR build+test 게이트) 부재

## 메시징 설계 (main 확정)

- `main`: payment 취소 이벤트 = **OUTBOX 정식**(TX3 원자 outbox INSERT + 커밋 후 relay). ~~TX3 인라인~~은 2026-07-29 outbox 재설계로 대체(PROJECT.md D-003).
- `variant/aftercommit`·`variant/outbox`: 초기 메시징 실험 브랜치(provenance).
