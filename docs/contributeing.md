# Contributing guide

코드 작성 기준과 컨벤션을 정의한다.
Claude Code와 개발자 모두 이 기준을 따른다.

---

## 아키텍처 방향

### 레이어 구조

```
presentation  → application → domain
infrastructure → domain

의존은 항상 안쪽을 향한다.
역방향 의존은 빌드 실패로 처리한다. (ArchUnit)
```

### 레이어별 역할

```
domain
  엔티티, 값객체, 도메인 서비스, 정책 객체, 도메인 이벤트
  Spring, JPA, Kafka를 알지 못한다.
  순수 Java로만 작성한다.
  예: Payment, CancelRequest, CancelAmountPolicy

application
  유스케이스, 인터페이스 선언, 트랜잭션 경계
  도메인을 조율하되 프레임워크 코드를 포함하지 않는다.
  예: CancelUseCase, MerchantLimitPort, EventPublisher

infrastructure
  인터페이스 구현체
  직렬화, DB 매핑, 외부 벤더 연동이 여기에 속한다.
  예: CancelRequestJpaAdapter, KafkaEventPublisher,
      MerchantLimitHttpClient

presentation
  외부 입력을 유스케이스 커맨드로 변환한다.
  검증 → 매핑 → 위임만 수행한다.
  예: CancelController, CancelRequestDto
```

---

## 네이밍 컨벤션

### 클래스 네이밍

| 레이어 | 접미사 | 예시 |
|--------|--------|------|
| 엔티티 | 없음 | `Payment`, `CancelRequest` |
| 값객체 | 없음 | `Money`, `CancelAmount` |
| 유스케이스 인터페이스 | `UseCase` | `CancelPaymentUseCase` |
| 유스케이스 구현체 | `Service` | `CancelPaymentService` |
| 외부 시스템 인터페이스 | `Port` | `MerchantLimitPort` |
| JPA 구현체 | `JpaAdapter` | `CancelRequestJpaAdapter` |
| Kafka 구현체 | `KafkaAdapter` | `CancelEventKafkaAdapter` |
| HTTP 클라이언트 | `HttpClient` | `MerchantLimitHttpClient` |
| 정책 객체 | `Policy` | `CancelAmountPolicy` |
| 도메인 서비스 | `DomainService` | `CancelDomainService` |
| 컨트롤러 | `Controller` | `CancelController` |
| 요청 DTO | `Request` | `CancelPaymentRequest` |
| 응답 DTO | `Response` | `CancelPaymentResponse` |
| 커맨드 | `Command` | `CancelPaymentCommand` |

### 메서드 네이밍

```
유스케이스 진입점: execute(), cancel(), confirm()
조회: find(), get(), load()
저장: save(), store()
검증: validate(), verify(), check()
변환: toCommand(), toResponse(), toDomain()
발행: publish(), dispatch()
```

### 패키지 네이밍

```
com.example.payment.domain.entity
com.example.payment.domain.service
com.example.payment.domain.policy
com.example.payment.application.usecase
com.example.payment.application.service
com.example.payment.application.interfaces
com.example.payment.infrastructure.persistence
com.example.payment.infrastructure.messaging
com.example.payment.infrastructure.http
com.example.payment.infrastructure.config
com.example.payment.presentation.controller
com.example.payment.presentation.dto
```

---

## Effective Java 스타일

### 정적 팩토리 메서드

의미 있는 생성에는 정적 팩토리 메서드를 사용한다.
생성자를 직접 노출하지 않는다.

```java
// 나쁜 예
new CancelRequest(paymentId, amount, reason);

// 좋은 예
CancelRequest.of(paymentId, amount, reason);
CancelRequest.withFullAmount(paymentId, reason);
```

### null 금지

null을 반환하거나 매개변수로 받지 않는다.

```java
// 나쁜 예
public CancelRequest findById(Long id) {
    return repository.findById(id); // null 가능
}

// 좋은 예
public Optional<CancelRequest> findById(Long id) {
    return repository.findById(id);
}

// 또는 예외로 표현
public CancelRequest getById(Long id) {
    return repository.findById(id)
        .orElseThrow(() -> new CancelRequestNotFoundException(id));
}
```

### 불변성

가능하면 불변 객체를 사용한다.
값객체는 반드시 불변이어야 한다.

```java
// 값객체 예시
public final class Money {
    private final BigDecimal amount;
    private final String currency;

    private Money(BigDecimal amount, String currency) {
        this.amount = amount;
        this.currency = currency;
    }

    public static Money of(BigDecimal amount, String currency) {
        return new Money(amount, currency);
    }

    public Money add(Money other) {
        // this를 변경하지 않고 새 객체 반환
        return new Money(this.amount.add(other.amount), this.currency);
    }
}
```

### 인터페이스 반환

구현체가 아닌 인터페이스를 반환한다.

```java
// 나쁜 예
public ArrayList<CancelRequest> findAll() { ... }

// 좋은 예
public List<CancelRequest> findAll() { ... }
```

### 가시성

가시성을 최대한 좁게 유지한다.

```java
public class CancelPaymentService {
    // 외부에 노출할 필요 없는 메서드는 private
    private void validateCancelAmount(Money amount) { ... }
    private void updatePaymentStatus(Payment payment) { ... }
}
```

---

## 메서드 길이 정책

```
비즈니스 메서드: 10줄 이하
Early return 선호
분기가 늘어나면 협력자 추출

예외:
  설정 코드
  단순 데이터 매핑
  프레임워크 연결 코드
```

### Early return 예시

```java
// 나쁜 예 (중첩 조건)
public void cancel(CancelCommand command) {
    if (payment != null) {
        if (payment.isCancellable()) {
            if (limit.isAvailable(command.amount())) {
                // 처리
            }
        }
    }
}

// 좋은 예 (early return)
public void cancel(CancelCommand command) {
    validatePaymentExists(payment);
    validateCancellable(payment);
    validateLimitAvailable(limit, command.amount());
    // 처리
}
```

---

## 선호 패턴

### Strategy — 변하는 비즈니스 규칙

```java
// 취소 가능 여부 정책
public interface CancellablePolicy {
    void validate(Payment payment);
}

public class PaymentStatusCancellablePolicy implements CancellablePolicy {
    @Override
    public void validate(Payment payment) {
        if (!CANCELLABLE_STATUSES.contains(payment.getStatus())) {
            throw new InvalidPaymentStatusException(payment.getStatus());
        }
    }
}
```

### Policy Object — 검증, 규칙 캡슐화

```java
public class CancelAmountPolicy {
    public void validate(PaymentItem item, Money cancelAmount) {
        Money available = item.getAmount().subtract(item.getCancelledAmount());
        if (cancelAmount.isGreaterThan(available)) {
            throw new CancelAmountExceededException(cancelAmount, available);
        }
    }
}
```

### Factory / Static Factory — 의미 있는 객체 생성

```java
public class CancelRequest {
    public static CancelRequest create(
        Payment payment,
        Money cancelAmount,
        String reason,
        CancellerType cancellerType,
        Long cancelledBy,
        String idempotencyKey
    ) {
        return new CancelRequest(
            payment.getId(),
            cancelAmount,
            reason,
            cancellerType,
            cancelledBy,
            idempotencyKey,
            CancelRequestStatus.PENDING
        );
    }
}
```

### Adapter — 외부 연동 캡슐화

```java
// 인터페이스 (application 레이어)
public interface MerchantLimitPort {
    MerchantDailyLimit getDailyLimit(Long merchantId, LocalDate kstDate);
}

// 구현체 (infrastructure 레이어)
public class MerchantLimitHttpClient implements MerchantLimitPort {
    @Override
    public MerchantDailyLimit getDailyLimit(Long merchantId, LocalDate kstDate) {
        // HTTP 호출 구현
    }
}
```

---

## 동시성 처리 규칙

### FOR UPDATE 사용 대상

가맹점 취소한도 차감은 반드시 비관적 락을 사용한다.

```java
@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("SELECT m FROM MerchantCancelUsage m " +
       "WHERE m.merchantId = :merchantId " +
       "AND m.kstDate = :kstDate")
Optional<MerchantCancelUsage> findByMerchantIdAndDateForUpdate(
    Long merchantId, LocalDate kstDate);
```

### 멱등성 처리

UK 제약 위반을 잡아서 멱등 응답으로 변환한다.

```java
try {
    idempotencyKeyRepository.save(idempotencyKey);
} catch (DataIntegrityViolationException e) {
    return idempotencyKeyRepository
        .findByIdemKey(key)
        .map(IdempotencyKey::getResponseBody)
        .orElseThrow();
}
```

---

## 테스트 컨벤션

### 테스트 이름

동작을 표현한다. 영어로 작성한다.
`should_{expected_behavior}_when_{condition}` 형식을 따른다.

```java
@Test
void should_throw_exception_when_cancel_amount_exceeds_available_amount() { }

@Test
void should_return_existing_result_when_same_idempotency_key_requested() { }

@Test
void should_reject_entire_amount_when_merchant_daily_limit_exceeded() { }
```

### Given / When / Then

```java
@Test
void should_throw_exception_when_cancel_amount_exceeds_available_amount() {
    // given
    PaymentItem item = PaymentItem.builder()
        .amount(Money.of(new BigDecimal("100000"), "KRW"))
        .cancelledAmount(Money.of(new BigDecimal("70000"), "KRW"))
        .build();
    Money cancelAmount = Money.of(new BigDecimal("50000"), "KRW");

    // when & then
    assertThatThrownBy(() -> policy.validate(item, cancelAmount))
        .isInstanceOf(CancelAmountExceededException.class);
}
```

### 테스트 레이어별 어노테이션

```java
// domain 테스트 - 어노테이션 없음
class CancelAmountPolicyTest { }

// application 테스트
@ExtendWith(MockitoExtension.class)
class CancelPaymentServiceTest { }

// infrastructure 테스트
@DataJpaTest
@AutoConfigureTestDatabase(replace = NONE)
class CancelRequestJpaAdapterTest { }

// presentation 테스트
@WebMvcTest(CancelController.class)
class CancelControllerTest { }
```

---

## Lombok 사용 정책

### 허용 어노테이션

| 어노테이션 | 용도 |
|-----------|------|
| `@Getter` | 필드 조회 |
| `@Builder` | 객체 생성 (access = PRIVATE 권장) |
| `@ToString` | 디버깅 |
| `@EqualsAndHashCode` | 값객체 동등성 비교 |
| `@Slf4j` | 로거 생성 |
| `@RequiredArgsConstructor` | 인프라/설정 클래스의 의존성 주입 |

### 금지 어노테이션

| 어노테이션 | 금지 이유 |
|-----------|---------|
| `@Setter` | 불변성 원칙 위반 |
| `@Data` | @Setter 포함 |
| `@AllArgsConstructor` | 정적 팩토리 메서드 원칙 위반 |
| `@NoArgsConstructor` | 도메인 엔티티에서 의미 없는 생성 허용 |

### 사용 예시

```java
// 도메인 엔티티
@Getter
@Builder(access = AccessLevel.PRIVATE)
public class CancelRequest {
 
    private final Long id;
    private final Long paymentId;
    private final BigDecimal cancelAmount;
    private final CancelRequestStatus status;
 
    // 외부에서는 정적 팩토리 메서드로만 생성
    public static CancelRequest create(Long paymentId, BigDecimal amount) {
        return CancelRequest.builder()
            .paymentId(paymentId)
            .cancelAmount(amount)
            .status(CancelRequestStatus.PENDING)
            .build();
    }
}
 
// 서비스 (의존성 주입)
@Slf4j
@RequiredArgsConstructor
public class CancelPaymentService implements CancelPaymentUseCase {
 
    private final CancelRequestRepository cancelRequestRepository;
    private final MerchantLimitPort merchantLimitPort;
}
```

---

## 커밋 메시지 컨벤션

```
타입: 제목 (50자 이내)

본문 (선택, 72자 줄바꿈)

타입 목록:
  feat     새 기능
  fix      버그 수정
  refactor 리팩토링 (기능 변경 없음)
  test     테스트 추가/수정
  docs     문서 수정
  chore    빌드, 설정 변경

예시:
  feat: 결제 취소 멱등성 처리 추가
  fix: 가맹점 한도 초과 시 잘못된 에러코드 반환 수정
  test: 동시 취소 요청 동시성 테스트 추가
```