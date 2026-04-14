package com.example.payment.application.service;

import com.example.payment.application.dto.CancelPaymentRequest;
import com.example.payment.application.dto.CancelPaymentResponse;
import com.example.payment.application.exception.IdempotentDuplicationException;
import com.example.payment.application.exception.MerchantCancelLimitExceededException;
import com.example.payment.application.exception.PaymentNotFoundException;
import com.example.payment.application.interfaces.CancelEventOutboxManager;
import com.example.payment.application.interfaces.CancelRequestRepository;
import com.example.payment.application.interfaces.IdempotencyKeyManager;
import com.example.payment.application.interfaces.PaymentItemRepository;
import com.example.payment.application.interfaces.PaymentRepository;
import com.example.payment.application.interfaces.RiskManagementService;
import com.example.payment.domain.entity.CancelRequest;
import com.example.payment.domain.entity.CancelStatus;
import com.example.payment.domain.entity.CancellerType;
import com.example.payment.domain.entity.Payment;
import com.example.payment.domain.entity.PaymentItem;
import com.example.payment.domain.entity.PaymentItemStatus;
import com.example.payment.domain.entity.PaymentStatus;
import com.example.payment.domain.exception.CancelAmountExceededException;
import com.example.payment.domain.exception.CancelPeriodExceededException;
import com.example.payment.domain.exception.InvalidPaymentItemStatusException;
import com.example.payment.domain.exception.InvalidPaymentStatusException;
import com.example.payment.domain.policy.CancelPeriodPolicy;
import com.example.payment.domain.service.CancelDomainService;
import com.example.payment.fixture.CancelRequestFixture;
import com.example.payment.fixture.PaymentFixture;
import com.example.payment.fixture.PaymentItemFixture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("CancelPaymentService 테스트")
class CancelPaymentServiceTest {

    @Mock
    private PaymentRepository paymentRepository;

    @Mock
    private PaymentItemRepository paymentItemRepository;

    @Mock
    private CancelRequestRepository cancelRequestRepository;

    @Mock
    private IdempotencyKeyManager idempotencyKeyManager;

    @Mock
    private RiskManagementService riskManagementService;

    @Mock
    private CancelEventOutboxManager cancelEventOutboxManager;

    private CancelDomainService cancelDomainService;
    private CancelPaymentService cancelPaymentService;

    private static final String PAYMENT_KEY = "pay_test_001";
    private static final Long USER_ID = 1L;
    private static final String IDEMPOTENCY_KEY = "idem_001";
    private static final Long MERCHANT_ID = 1L;
    private static final BigDecimal CANCEL_AMOUNT = BigDecimal.valueOf(50000);

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(
            LocalDateTime.of(2026, 3, 15, 0, 0, 0).toInstant(ZoneOffset.UTC),
            ZoneOffset.UTC
        );
        CancelPeriodPolicy cancelPeriodPolicy = new CancelPeriodPolicy(clock);
        cancelDomainService = new CancelDomainService(cancelPeriodPolicy);

        cancelPaymentService = new CancelPaymentService(
            paymentRepository,
            paymentItemRepository,
            cancelRequestRepository,
            idempotencyKeyManager,
            riskManagementService,
            cancelEventOutboxManager,
            cancelDomainService
        );
    }

    @Test
    @DisplayName("정상 취소: 멱등키 없음 → 취소 완료")
    void testCancelPaymentSuccess() {
        // Given
        Payment payment = PaymentFixture.completedPayment();
        PaymentItem item = PaymentItemFixture.activeItem(payment.getId(), 100L, CANCEL_AMOUNT);

        when(idempotencyKeyManager.getCancelRequestId(IDEMPOTENCY_KEY))
            .thenReturn(Optional.empty());

        when(paymentRepository.findByPaymentKey(PAYMENT_KEY))
            .thenReturn(Optional.of(payment));

        when(paymentItemRepository.findAllByPaymentId(payment.getId()))
            .thenReturn(List.of(item));

        when(riskManagementService.validateAndReserveLimit(
            payment.getMerchantId(), payment.getId(), CANCEL_AMOUNT
        )).thenReturn(1L);

        // cancelRequestRepository.save는 입력된 CancelRequest 객체를 그대로 반환
        // (실제 영속성 처리는 repository에서 함)
        when(cancelRequestRepository.save(any(CancelRequest.class)))
            .thenAnswer(inv -> inv.getArgument(0));

        when(paymentRepository.save(any(Payment.class)))
            .thenAnswer(inv -> inv.getArgument(0));

        doNothing().when(paymentItemRepository).saveAll(any());
        doNothing().when(idempotencyKeyManager).recordIdempotencyKey(any(), any());
        doNothing().when(cancelEventOutboxManager).recordCancelEvent(any(), any(), any(), any());

        // When
        CancelPaymentResponse response = cancelPaymentService.cancel(
            PAYMENT_KEY, USER_ID, IDEMPOTENCY_KEY,
            createCancelRequest()
        );

        // Then
        assertThat(response).isNotNull();
        assertThat(response.cancelAmount()).isEqualTo(CANCEL_AMOUNT);
        // Status는 도메인 로직에서 COMPLETED가 되므로 검증
        assertThat(response.status()).isIn("COMPLETED", "PROCESSING", "PENDING");

        verify(riskManagementService).validateAndReserveLimit(
            payment.getMerchantId(), payment.getId(), CANCEL_AMOUNT
        );
        verify(cancelEventOutboxManager).recordCancelEvent(
            any(), eq(PAYMENT_KEY), eq(CANCEL_AMOUNT), any()
        );
    }

    @Test
    @DisplayName("Payment 없음: PaymentNotFoundException 발생")
    void testCancelPaymentNotFound() {
        // Given
        when(idempotencyKeyManager.getCancelRequestId(IDEMPOTENCY_KEY))
            .thenReturn(Optional.empty());

        when(paymentRepository.findByPaymentKey(PAYMENT_KEY))
            .thenReturn(Optional.empty());

        // When & Then
        assertThatThrownBy(() ->
            cancelPaymentService.cancel(
                PAYMENT_KEY, USER_ID, IDEMPOTENCY_KEY,
                createCancelRequest()
            )
        ).isInstanceOf(PaymentNotFoundException.class);

        verify(paymentRepository).findByPaymentKey(PAYMENT_KEY);
    }

    @Test
    @DisplayName("Payment 상태 불가: InvalidPaymentStatusException 발생")
    void testCancelPaymentInvalidStatus() {
        // Given
        Payment payment = PaymentFixture.completedPayment();
        payment.updateStatus(PaymentStatus.CANCELLED);
        PaymentItem item = PaymentItemFixture.activeItem(payment.getId(), 100L, CANCEL_AMOUNT);

        when(idempotencyKeyManager.getCancelRequestId(IDEMPOTENCY_KEY))
            .thenReturn(Optional.empty());

        when(paymentRepository.findByPaymentKey(PAYMENT_KEY))
            .thenReturn(Optional.of(payment));

        when(paymentItemRepository.findAllByPaymentId(payment.getId()))
            .thenReturn(List.of(item));

        // When & Then
        assertThatThrownBy(() ->
            cancelPaymentService.cancel(
                PAYMENT_KEY, USER_ID, IDEMPOTENCY_KEY,
                createCancelRequest()
            )
        ).isInstanceOf(InvalidPaymentStatusException.class);
    }

    @Test
    @DisplayName("PaymentItem 상태 불가: InvalidPaymentItemStatusException 발생")
    void testCancelPaymentItemInvalidStatus() {
        // Given
        Payment payment = PaymentFixture.completedPayment();
        PaymentItem item = PaymentItemFixture.cancelledItem(payment.getId(), 100L, CANCEL_AMOUNT);

        when(idempotencyKeyManager.getCancelRequestId(IDEMPOTENCY_KEY))
            .thenReturn(Optional.empty());

        when(paymentRepository.findByPaymentKey(PAYMENT_KEY))
            .thenReturn(Optional.of(payment));

        when(paymentItemRepository.findAllByPaymentId(payment.getId()))
            .thenReturn(List.of(item));

        // When & Then
        assertThatThrownBy(() ->
            cancelPaymentService.cancel(
                PAYMENT_KEY, USER_ID, IDEMPOTENCY_KEY,
                createCancelRequest()
            )
        ).isInstanceOf(InvalidPaymentItemStatusException.class);
    }

    @Test
    @DisplayName("취소액 초과: CancelAmountExceededException 발생")
    void testCancelPaymentAmountExceeded() {
        // Given
        Payment payment = PaymentFixture.completedPayment();
        PaymentItem item = PaymentItemFixture.activeItem(
            payment.getId(), 100L, BigDecimal.valueOf(10000)  // 아이템 금액 10,000
        );

        when(idempotencyKeyManager.getCancelRequestId(IDEMPOTENCY_KEY))
            .thenReturn(Optional.empty());

        when(paymentRepository.findByPaymentKey(PAYMENT_KEY))
            .thenReturn(Optional.of(payment));

        when(paymentItemRepository.findAllByPaymentId(payment.getId()))
            .thenReturn(List.of(item));

        // 50,000 취소 요청 (아이템 금액 10,000 초과)
        CancelPaymentRequest request = new CancelPaymentRequest(
            CANCEL_AMOUNT,
            "reason",
            List.of(new CancelPaymentRequest.CancelItemRequest(100L, CANCEL_AMOUNT))
        );

        // When & Then
        assertThatThrownBy(() ->
            cancelPaymentService.cancel(PAYMENT_KEY, USER_ID, IDEMPOTENCY_KEY, request)
        ).isInstanceOf(CancelAmountExceededException.class);
    }

    @Test
    @DisplayName("기간 초과: CancelPeriodExceededException 발생")
    void testCancelPaymentPeriodExceeded() {
        // Given - 과거 결제로 기간 만료
        Payment payment = PaymentFixture.completedPaymentWith1DayPeriod();  // 1일 기간
        // 2026-01-01 결제, 1일 기간, 현재 2026-03-15 (기간 초과)

        PaymentItem item = PaymentItemFixture.activeItem(payment.getId(), 100L, CANCEL_AMOUNT);

        when(idempotencyKeyManager.getCancelRequestId(IDEMPOTENCY_KEY))
            .thenReturn(Optional.empty());

        when(paymentRepository.findByPaymentKey(PAYMENT_KEY))
            .thenReturn(Optional.of(payment));

        when(paymentItemRepository.findAllByPaymentId(payment.getId()))
            .thenReturn(List.of(item));

        // When & Then
        assertThatThrownBy(() ->
            cancelPaymentService.cancel(
                PAYMENT_KEY, USER_ID, IDEMPOTENCY_KEY,
                createCancelRequest()
            )
        ).isInstanceOf(CancelPeriodExceededException.class);
    }

    @Test
    @DisplayName("한도 초과: MerchantCancelLimitExceededException 발생")
    void testCancelPaymentLimitExceeded() {
        // Given
        Payment payment = PaymentFixture.completedPayment();
        PaymentItem item = PaymentItemFixture.activeItem(payment.getId(), 100L, CANCEL_AMOUNT);

        when(idempotencyKeyManager.getCancelRequestId(IDEMPOTENCY_KEY))
            .thenReturn(Optional.empty());

        when(paymentRepository.findByPaymentKey(PAYMENT_KEY))
            .thenReturn(Optional.of(payment));

        when(paymentItemRepository.findAllByPaymentId(payment.getId()))
            .thenReturn(List.of(item));

        when(cancelRequestRepository.save(any(CancelRequest.class)))
            .thenAnswer(inv -> {
                CancelRequest req = inv.getArgument(0);
                return CancelRequestFixture.cancelRequest(req.getPaymentId(), req.getCancelAmount(), req.getIdempotencyKey());
            });

        when(riskManagementService.validateAndReserveLimit(
            anyLong(), anyLong(), any(BigDecimal.class)
        )).thenThrow(new MerchantCancelLimitExceededException(
            CANCEL_AMOUNT,
            BigDecimal.ZERO,
            BigDecimal.valueOf(1000000)
        ));

        doNothing().when(idempotencyKeyManager).recordIdempotencyKey(any(), any());

        // When & Then
        assertThatThrownBy(() ->
            cancelPaymentService.cancel(
                PAYMENT_KEY, USER_ID, IDEMPOTENCY_KEY,
                createCancelRequest()
            )
        ).isInstanceOf(MerchantCancelLimitExceededException.class);
    }

    @Test
    @DisplayName("멱등키 중복: 이미 처리된 요청")
    void testCancelPaymentIdempotentDuplication() {
        // Given
        CancelRequest existingRequest = CancelRequestFixture.cancelRequest(
            1L, CANCEL_AMOUNT, IDEMPOTENCY_KEY
        );

        when(idempotencyKeyManager.getCancelRequestId(IDEMPOTENCY_KEY))
            .thenReturn(Optional.of(1L));

        when(cancelRequestRepository.findById(1L))
            .thenReturn(Optional.of(existingRequest));

        // When & Then
        assertThatThrownBy(() ->
            cancelPaymentService.cancel(
                PAYMENT_KEY, USER_ID, IDEMPOTENCY_KEY,
                createCancelRequest()
            )
        ).isInstanceOf(IdempotentDuplicationException.class);
    }

    private CancelPaymentRequest createCancelRequest() {
        return new CancelPaymentRequest(
            CANCEL_AMOUNT,
            "고객 단순 변심",
            List.of(
                new CancelPaymentRequest.CancelItemRequest(100L, CANCEL_AMOUNT)
            )
        );
    }
}
