# Agent rules

Claude Code가 이 프로젝트에서 작업할 때 따르는 행동 규칙이다.
코드보다 이 규칙이 먼저다.

---

## 작업 시작 전 체크리스트

모든 작업 시작 전 아래를 순서대로 확인한다.

```
1. CLAUDE.md 읽기 완료 여부 확인
2. 관련 도메인 규칙 확인 (@docs/domain-rules.md)
3. 관련 API 스펙 확인 (@docs/api-spec.md)
4. 관련 DDL 확인 (db/migration/*.sql)
5. 에러 코드 확인 (@docs/error-catalog.md)
6. 취소 관련 작업이면 cancel-design.md 확인
   (TX 경계, 이력 저장 원칙, daily_limit 조회 순서 등)
```

문서를 읽지 않고 코드를 작성하지 않는다.

---

## TDD 워크플로우

반드시 이 순서를 따른다. 순서를 바꾸지 않는다.

```
1. 테스트 하나 작성 (실패하는 테스트)
2. 테스트를 통과시키는 최소한의 코드 작성
3. 리팩토링 (이름, 경계, 중복 제거)
4. 관련 테스트 전체 재실행
5. 다음 동작으로 이동
```

한 번에 하나의 동작만 다룬다.
테스트가 없는 코드는 완료로 간주하지 않는다.

---

## 코드 작성 규칙

### 레이어 경계

```
domain 레이어:
  Spring, JPA, Kafka 어노테이션 사용 금지
  외부 시스템 직접 호출 금지
  순수 Java로만 작성

application 레이어:
  프레임워크 의존 최소화
  인터페이스를 통해서만 외부 시스템 호출
  트랜잭션 경계는 여기서 결정

infrastructure 레이어:
  인터페이스 구현체
  프레임워크/벤더 코드 허용
  도메인 레이어 의존 금지

presentation 레이어:
  입력 검증 → 매핑 → 위임만 수행
  비즈니스 로직 금지
```

### 메서드 길이 정책

```
비즈니스 메서드: 10줄 이하 유지
Early return 선호 (중첩 조건 대신)
로직 확장 시 → Policy, Validator, Factory, Mapper로 분리

예외:
  설정 코드
  단순 데이터 매핑
  프레임워크 연결 코드
```

### 금지 패턴

```
- null 반환 금지 → Optional, 빈 컬렉션, 명시적 실패 타입 사용
- 마법 숫자/문자열 하드코딩 금지 → 상수 또는 enum으로 추출
- if/when 분기 3개 초과 시 Strategy 또는 Policy로 대체
- 도메인 로직에서 외부 시스템 직접 호출 금지
- 모듈 간 DB 직접 접근 금지
- 시크릿/비밀번호 코드 하드코딩 금지
```

---

## 비즈니스 규칙 처리

```
취소 금액 검증, 상태 전이, 한도 계산 등
비즈니스 판단이 필요한 모든 코드는
docs/domain-rules.md를 먼저 확인한다.

문서에 규칙이 있으면 → 문서대로 구현
문서에 규칙이 없으면 → 가정을 명시하고 진행
  형식: "가정: [내용]. domain-rules.md 업데이트 필요."
```

---

## 에러 처리 규칙

```
모든 에러 코드와 메시지는 docs/error-catalog.md를 따른다.
에러 카탈로그에 없는 에러가 필요하면
  1. 먼저 카탈로그에 추가
  2. 그 다음 코드 작성

에러 응답 포맷:
{
  "code": "ERROR_CODE",
  "message": "한국어 메시지",
  "detail": {}
}

도메인 예외는 domain 레이어에서 정의.
HTTP 상태코드 매핑은 presentation 레이어에서 처리.
```

---

## 테스트 작성 규칙

### 테스트 이름

```
동작을 표현한다.
메서드 이름이 아닌 동작 이름으로 작성한다.
`should_{expected_behavior}_when_{condition}` 형식을 따른다.

나쁜 예: testCancelPayment()
좋은 예: should_return_422_when_cancel_amount_exceeds_available_amount()
         should_reject_entire_amount_when_merchant_daily_limit_exceeded()
         should_return_existing_result_when_same_idempotency_key_requested()
```

### 테스트 레이어별 범위

```
domain 테스트:
  Spring 컨텍스트 없이 순수 Java로
  비즈니스 불변 조건 검증
  상태 전이 검증

application 테스트:
  Mock을 사용해 외부 의존 격리
  유스케이스 오케스트레이션 검증
  트랜잭션 경계 검증

infrastructure 테스트:
  Testcontainers로 실제 DB/Kafka 사용
  JPA 매핑 검증
  Kafka 직렬화/역직렬화 검증

presentation 테스트:
  MockMvc 사용
  입력 검증, 응답 포맷 검증
  에러 응답 형식 검증
```

### 동시성 테스트 필수 대상

```
아래 항목은 반드시 동시성 테스트를 작성한다.

- 가맹점 취소한도 차감 (FOR UPDATE)
  동일 가맹점에 동시 요청 시 한 건만 성공해야 함

- 멱등키 중복 (UK 제약)
  동일 request_hash 동시 요청 시 하나만 처리해야 함

- 동시 취소 시 Payment 상태 재계산 (TX 3 FOR UPDATE)
  유저 A가 PaymentItem 1 취소 + 가맹점 B가 PaymentItem 2 동시 취소 시
  Payment 최종 상태가 CANCELLED로 정확히 반영되어야 함
  (TX 3에서 findAllByPaymentIdForUpdate() 재조회 검증)
```

---

## 완료 기준 (Definition of Done)

아래를 모두 충족해야 완료다.

```
- [ ] 관련 테스트가 모두 통과한다
- [ ] 새로 작성한 코드에 테스트가 있다
- [ ] domain 레이어에 프레임워크 의존이 없다
- [ ] 메서드가 10줄 이하다 (예외 항목 제외)
- [ ] 에러 처리가 error-catalog.md를 따른다
- [ ] 비즈니스 규칙이 domain-rules.md와 일치한다
- [ ] 모듈 간 DB 직접 접근이 없다
- [ ] 시크릿이 코드에 없다
```

---

## 자기 검토 체크리스트

코드 작성 후 제출 전 확인한다.

```
- [ ] 테스트를 먼저 작성했는가?
- [ ] 테스트 이름이 동작을 표현하는가?
- [ ] 메서드를 10줄 이내로 줄일 수 있는가?
- [ ] if/when 분기를 Strategy 또는 Policy로 대체할 수 있는가?
- [ ] domain 로직에 프레임워크 의존이 없는가?
- [ ] 이름이 의도를 드러내는가?
- [ ] 중복이 제거됐는가?
- [ ] null을 반환하거나 받는 곳이 없는가?
- [ ] domain-rules.md와 충돌하는 로직이 없는가?

[취소 플로우 전용]
- [ ] 이력(cancel_request_history)이 TX 안에 포함됐는가?
     (포함됐다면 TX 밖으로 분리 필요)
- [ ] TX 3에서 PaymentItem을 findAllByPaymentIdForUpdate()로 재조회했는가?
     (조회 시점 데이터 사용 금지)
- [ ] daily_limit 조회 순서가 올바른가?
     (Redis → DB 스냅샷 → merchant-limit HTTP 순서 준수)
- [ ] FAILED 건 재시도 시 새 INSERT 대신 PENDING UPDATE를 했는가?
```