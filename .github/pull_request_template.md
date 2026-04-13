# 요약 (Summary)

변경 사항에 대한 간결한 설명을 작성하세요. 비즈니스 규칙, 동기, 배경을 포함하세요.

**관련 이슈**: Fixes # (issue number, 있으면)

---

## 변경 유형 (Type of change)

해당하는 항목에 체크하세요:

- [ ] 🐛 버그 수정 (기능이 깨지지 않는 수정)
- [ ] ✨ 새 기능 추가 (새로운 기능 추가)
- [ ] 🚨 Breaking change (기존 기능이 동작하지 않을 수 있음)
- [ ] 📚 문서 업데이트 필요

---

## 상세 설명 (Details)

### 비즈니스 규칙 (Business Rules)

이 PR이 영향을 주는 비즈니스 규칙을 명시하세요:
- [ ] `@docs/domain-rules.md` 확인 완료
- 영향받는 규칙 (예: 취소 가능 조건, 상태 전이, 한도 검증 등)

### API 변경 (API Changes - 해당하면)

- [ ] `@docs/api-spec.md` 확인 완료
- [ ] `@docs/error-catalog.md`에 새로운 에러 코드 추가 (필요시)
- 변경된 엔드포인트:
- 새로운 응답 포맷 (있으면):

### DB 변경 (Database Changes - 해당하면)

- [ ] `@docs/db-schema.md` 확인 완료
- [ ] Flyway 마이그레이션 파일 작성 (V**__*.sql)
- 변경 사항:

### Kafka 변경 (Kafka Changes - 해당하면)

- [ ] `@docs/kafka-design.md` 확인 완료
- 토픽:
- 파티션 키:
- 스키마 변경:

### 모듈 간 통신 (Inter-module Communication - 해당하면)

- [ ] HTTP 호출 추가 (Circuit Breaker 적용?)
- [ ] Kafka 소비자/생산자 추가
- 의존 모듈:

---

## 테스트 방법 (How Has This Been Tested?)

### 테스트 작성 (Testing)

- [ ] **Domain 레이어 테스트**: 순수 Java, Spring 없음
- [ ] **Application 레이어 테스트**: Mockito로 외부 의존 격리
- [ ] **Infrastructure 테스트**: Testcontainers (실제 DB/Kafka)
- [ ] **Presentation 테스트**: MockMvc로 HTTP 검증
- [ ] **동시성 테스트** (멱등성, 한도 차감 등 필요시)

### 재현 방법 (Reproduction Steps)

```bash
# 로컬 빌드 및 테스트
./gradlew :payment-service:test
./gradlew :order-service:test
# (변경된 모듈 테스트)
```

### 테스트 설정 (Test Configuration)

* Java: 21
* Spring Boot: 3.x
* DB: MySQL 8.0 (via Testcontainers)
* Kafka: 3.x cluster (via Testcontainers)
* ORM: Spring Data JPA + QueryDSL
* 테스트 프레임워크: JUnit 5 + Mockito

---

## 아키텍처 검증 (Architecture Compliance)

### 레이어 분리 (Layer Boundaries)

- [ ] **Domain**: Spring 어노테이션 없음, 외부 시스템 호출 없음
- [ ] **Application**: 프레임워크 최소화, 인터페이스를 통해 외부 호출
- [ ] **Infrastructure**: 프레임워크/벤더 코드 허용
- [ ] **Presentation**: 입력 검증 → 위임만 수행

### 의존성 규칙 (Dependency Rules)

- [ ] Presentation → Application → Domain (역방향 없음)
- [ ] Infrastructure → Domain (역방향 없음)
- [ ] 모듈 간 DB 직접 접근 없음

### 메서드 품질 (Method Quality)

- [ ] 메서드 길이 10줄 이하 (예외: 설정, 매핑 코드)
- [ ] null 반환 없음 (Optional, 빈 컬렉션, 명시적 실패 사용)
- [ ] if/when 분기 3개 이상 시 Strategy/Policy 패턴 적용

---

## 멱등성 검증 (Idempotency)

취소 관련 변경 시:
- [ ] 멱등키(Idempotency-Key) 유효 기간 확인
- [ ] DB 중복 방어 (UK 제약, processed_*_event 테이블 등)
- [ ] 멱등성 테스트 작성 (동시 요청 시뮬레이션)

---

## 최종 체크리스트 (Checklist)

### 코드 품질

- [ ] 코드가 프로젝트 스타일 가이드를 따름 (@docs/contributing.md)
- [ ] 자체 코드 리뷰 완료
- [ ] 이해하기 어려운 부분에 주석 추가
- [ ] 새로운 경고 없음

### 테스트

- [ ] 모든 새로운 코드에 테스트 추가
- [ ] 로컬에서 모든 테스트 통과
- [ ] 기존 테스트도 실패하지 않음

### 문서

- [ ] API 변경 시 @docs/api-spec.md 업데이트
- [ ] 비즈니스 규칙 변경 시 @docs/domain-rules.md 업데이트
- [ ] DB 변경 시 @docs/db-schema.md 업데이트
- [ ] Kafka 변경 시 @docs/kafka-design.md 업데이트

### TDD 준수

- [ ] 테스트를 먼저 작성했는가?
- [ ] 테스트 이름이 동작을 표현하는가? (should_*_when_*)
- [ ] 단위 테스트 → 구현 → 리팩토링 순서 준수

---

## 추가 사항 (Additional Notes)

성능, 보안, 운영 영향도 등 특별히 주의할 사항이 있으면 작성하세요:
