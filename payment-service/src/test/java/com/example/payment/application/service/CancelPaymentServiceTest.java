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
import com.example.payment.domain.entity.Payment;
import com.example.payment.domain.entity.PaymentItem;
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
    void should_complete_cancel_when_no_duplicate_idempotency_key() {
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
        assertThat(response.status()).isEqualTo("COMPLETED");

        verify(riskManagementService).validateAndReserveLimit(
            payment.getMerchantId(), payment.getId(), CANCEL_AMOUNT
        );
        verify(cancelEventOutboxManager).recordCancelEvent(
            any(), eq(PAYMENT_KEY), eq(CANCEL_AMOUNT), any()
        );
    }

    @Test
    void should_throw_payment_not_found_when_payment_does_not_exist() {
        // Given
        when(idempotencyKeyManager.getCancelRequestId(IDEMPOTENCY_KEY))
            .thenReturn(Optional.empty());

        when(paymentRepository.findByPaymentKey(PAYMENT_KEY))
            .thenReturn(Optional.empty());

        CancelPaymentRequest cancelPaymentRequest = createCancelRequest();
        // When & Then
        assertThatThrownBy(() ->
            cancelPaymentService.cancel(
                PAYMENT_KEY, USER_ID, IDEMPOTENCY_KEY,
                cancelPaymentRequest
            )
        ).isInstanceOf(PaymentNotFoundException.class);

        verify(paymentRepository).findByPaymentKey(PAYMENT_KEY);
    }

    @Test
    void should_throw_invalid_payment_status_when_payment_is_already_cancelled() {
        // Given
        Payment payment = PaymentFixture.cancelledPayment();
        PaymentItem item = PaymentItemFixture.activeItem(payment.getId(), 100L, CANCEL_AMOUNT);

        when(idempotencyKeyManager.getCancelRequestId(IDEMPOTENCY_KEY))
            .thenReturn(Optional.empty());

        when(paymentRepository.findByPaymentKey("pay_test_cancelled"))
            .thenReturn(Optional.of(payment));

        when(paymentItemRepository.findAllByPaymentId(payment.getId()))
            .thenReturn(List.of(item));
        CancelPaymentRequest cancelPaymentRequest = createCancelRequest();

        // When & Then
        assertThatThrownBy(() ->
            cancelPaymentService.cancel(
                "pay_test_cancelled", USER_ID, IDEMPOTENCY_KEY,
                cancelPaymentRequest
            )
        ).isInstanceOf(InvalidPaymentStatusException.class);
    }

    @Test
    void should_throw_invalid_payment_item_status_when_item_is_already_cancelled() {
        // Given
        Payment payment = PaymentFixture.completedPayment();
        PaymentItem item = PaymentItemFixture.cancelledItem(payment.getId(), 100L, CANCEL_AMOUNT);

        when(idempotencyKeyManager.getCancelRequestId(IDEMPOTENCY_KEY))
            .thenReturn(Optional.empty());

        when(paymentRepository.findByPaymentKey(PAYMENT_KEY))
            .thenReturn(Optional.of(payment));

        when(paymentItemRepository.findAllByPaymentId(payment.getId()))
            .thenReturn(List.of(item));

        CancelPaymentRequest cancelPaymentRequest = createCancelRequest();

        // When & Then
        assertThatThrownBy(() ->
            cancelPaymentService.cancel(
                PAYMENT_KEY, USER_ID, IDEMPOTENCY_KEY,
                cancelPaymentRequest
            )
        ).isInstanceOf(InvalidPaymentItemStatusException.class);
    }

    @Test
    void should_throw_cancel_amount_exceeded_when_cancel_exceeds_item_amount() {
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
    void should_throw_cancel_period_exceeded_when_cancel_period_is_over() {
        // Given - 과거 결제로 기간 만료
        Payment payment = PaymentFixture.completedPaymentWith1DayPeriod();  // 1일 기간
        // 2026-01-01 결제, 1일 기간, 현재 2026-03-15 (기간 초과)

        PaymentItem item = PaymentItemFixture.activeItem(payment.getId(), 100L, CANCEL_AMOUNT);

        when(idempotencyKeyManager.getCancelRequestId(IDEMPOTENCY_KEY))
            .thenReturn(Optional.empty());

        when(paymentRepository.findByPaymentKey("pay_test_002"))
            .thenReturn(Optional.of(payment));

        when(paymentItemRepository.findAllByPaymentId(payment.getId()))
            .thenReturn(List.of(item));
        CancelPaymentRequest cancelPaymentRequest = createCancelRequest();
        // When & Then
        assertThatThrownBy(() ->
            cancelPaymentService.cancel(
                "pay_test_002", USER_ID, IDEMPOTENCY_KEY,
                cancelPaymentRequest

            )
        ).isInstanceOf(CancelPeriodExceededException.class);
    }

    @Test
    void should_throw_merchant_cancel_limit_exceeded_when_daily_limit_exhausted() {
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

        CancelPaymentRequest cancelPaymentRequest = createCancelRequest();

        // When & Then
        assertThatThrownBy(() ->
            cancelPaymentService.cancel(
                PAYMENT_KEY, USER_ID, IDEMPOTENCY_KEY,
                cancelPaymentRequest
            )
        ).isInstanceOf(MerchantCancelLimitExceededException.class);
    }

    @Test
    void should_throw_idempotent_duplication_when_idempotency_key_already_processed() {
        // Given
        CancelRequest existingRequest = CancelRequestFixture.cancelRequest(
            1L, CANCEL_AMOUNT, IDEMPOTENCY_KEY
        );

        when(idempotencyKeyManager.getCancelRequestId(IDEMPOTENCY_KEY))
            .thenReturn(Optional.of(1L));

        when(cancelRequestRepository.findById(1L))
            .thenReturn(Optional.of(existingRequest));

        CancelPaymentRequest cancelPaymentRequest = createCancelRequest();
        // When & Then
        assertThatThrownBy(() ->
            cancelPaymentService.cancel(
                PAYMENT_KEY, USER_ID, IDEMPOTENCY_KEY,
                cancelPaymentRequest
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
