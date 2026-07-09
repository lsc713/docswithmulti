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

- [x] payment-service (취소 플로우 + TX3 인라인 Kafka 발행)
- [x] order-service (Kafka Consumer + 상태 동기화)
- [x] merchant-limit-service (한도 원본 + `merchant.limit.updated` Outbox 발행)
- [x] risk-management-service (취소 검증 + 한도 소진)
- [ ] product-service (상품/SKU/재고) — 미구현

## 구현 우선순위 (남은 작업)

```
1. payment-service          핵심 취소 플로우        (완료)
2. risk-management-service  취소 검증 + 한도 소진    (완료)
3. merchant-limit-service   한도 원본 관리          (완료)
4. order-service            Kafka Consumer + 동기화  (완료)
5. product-service          상품/SKU/재고           (미구현)
```

## 메시징 설계 분기 (브랜치)

- `main`: payment 취소 이벤트 = **TX3 인라인 발행**
- `variant/aftercommit`: AFTER_COMMIT + failed_kafka_event
- `variant/outbox`: Outbox 패턴
- `feat/user-product-resilience`: inline + user/product 서비스 + 내구성 + JWT
