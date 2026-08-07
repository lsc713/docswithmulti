package com.example.payment.application.service;

import com.example.payment.application.authz.AuthenticatedUser;
import com.example.payment.application.exception.PaymentNotFoundException;
import com.example.payment.application.interfaces.CancelApprovalRepository;
import com.example.payment.application.interfaces.PaymentItemRepository;
import com.example.payment.application.interfaces.PaymentRepository;
import com.example.payment.application.usecase.CancelPaymentUseCase;
import com.example.payment.common.exception.domain.CancelApprovalNotFoundException;
import com.example.payment.common.exception.domain.CancelNotAuthorizedException;
import com.example.payment.common.exception.domain.DuplicateCancelRequestException;
import com.example.payment.domain.entity.CancelApproval;
import com.example.payment.domain.entity.CancelApprovalStatus;
import com.example.payment.domain.entity.CancelRequest;
import com.example.payment.domain.entity.Payment;
import com.example.payment.domain.entity.PaymentItem;
import com.example.payment.domain.entity.PaymentItemStatus;
import com.example.payment.fixture.PaymentFixture;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CancelApprovalService — request/list/approve/reject 오케스트레이션.
 *
 * 취소 코어 불변 확인: approve()가 기존 CancelPaymentUseCase#cancel 을 정확한 커맨드로
 * 호출하는지, 그리고 인가 실패/상태 충돌 경로에서는 cancel()이 절대 호출되지 않는지 검증한다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CancelApprovalService")
class CancelApprovalServiceTest {

    private static final String PAYMENT_KEY = "pay_test_001";

    @Mock CancelApprovalRepository cancelApprovalRepository;
    @Mock PaymentRepository paymentRepository;
    @Mock PaymentItemRepository paymentItemRepository;
    @Mock CancelPaymentUseCase cancelPaymentUseCase;

    private CancelApprovalService service;
    private Payment payment;

    @BeforeEach
    void setUp() {
        service = new CancelApprovalService(
            cancelApprovalRepository, paymentRepository, paymentItemRepository, cancelPaymentUseCase);
        // PaymentFixture.completedPayment(): merchantId=1, userId=1, paymentKey="pay_test_001"
        payment = PaymentFixture.completedPayment();
    }

    // ---- request ----

    @Test
    @DisplayName("request: 소유자 + 진행 중인 요청 없음 → REQUESTED 저장")
    void request_creates_REQUESTED_when_owner_and_no_active() {
        AuthenticatedUser owner = new AuthenticatedUser("1", "USER", null);
        when(paymentRepository.findByPaymentKey(PAYMENT_KEY)).thenReturn(Optional.of(payment));
        when(cancelApprovalRepository.findActiveRequestedByPaymentId(payment.getId())).thenReturn(Optional.empty());
        when(cancelApprovalRepository.save(any(CancelApproval.class))).thenAnswer(inv -> inv.getArgument(0));

        CancelApproval result = service.request(PAYMENT_KEY, owner, "단순 변심");

        ArgumentCaptor<CancelApproval> captor = ArgumentCaptor.forClass(CancelApproval.class);
        verify(cancelApprovalRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(CancelApprovalStatus.REQUESTED);
        assertThat(captor.getValue().getPaymentKey()).isEqualTo(PAYMENT_KEY);
        assertThat(captor.getValue().getReason()).isEqualTo("단순 변심");
        assertThat(result.getStatus()).isEqualTo(CancelApprovalStatus.REQUESTED);
    }

    @Test
    @DisplayName("request: 결제 없음 → PaymentNotFoundException")
    void request_missing_payment_throws() {
        AuthenticatedUser owner = new AuthenticatedUser("1", "USER", null);
        when(paymentRepository.findByPaymentKey(PAYMENT_KEY)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.request(PAYMENT_KEY, owner, "사유"))
            .isInstanceOf(PaymentNotFoundException.class);
    }

    @Test
    @DisplayName("request: 이미 진행 중인 요청 존재 → 409 DuplicateCancelRequestException")
    void request_duplicate_active_throws_409() {
        AuthenticatedUser owner = new AuthenticatedUser("1", "USER", null);
        when(paymentRepository.findByPaymentKey(PAYMENT_KEY)).thenReturn(Optional.of(payment));
        when(cancelApprovalRepository.findActiveRequestedByPaymentId(payment.getId()))
            .thenReturn(Optional.of(CancelApproval.request(payment.getId(), PAYMENT_KEY, 1L, "기존 요청")));

        assertThatThrownBy(() -> service.request(PAYMENT_KEY, owner, "사유"))
            .isInstanceOf(DuplicateCancelRequestException.class);

        verify(cancelApprovalRepository, never()).save(any());
    }

    @Test
    @DisplayName("request: 소유자가 아니면 인가 예외")
    void request_non_owner_throws() {
        AuthenticatedUser notOwner = new AuthenticatedUser("999", "USER", null);
        when(paymentRepository.findByPaymentKey(PAYMENT_KEY)).thenReturn(Optional.of(payment));

        assertThatThrownBy(() -> service.request(PAYMENT_KEY, notOwner, "사유"))
            .isInstanceOf(CancelNotAuthorizedException.class);

        verify(cancelApprovalRepository, never()).save(any());
    }

    // ---- list ----

    @Test
    @DisplayName("list: ADMIN → 필터 없이 findByStatus 그대로 반환")
    void list_admin_returns_all() {
        AuthenticatedUser admin = new AuthenticatedUser("1", "ADMIN", null);
        CancelApproval a = CancelApproval.request(1L, PAYMENT_KEY, 1L, "사유");
        when(cancelApprovalRepository.findByStatus(CancelApprovalStatus.REQUESTED)).thenReturn(List.of(a));

        List<CancelApproval> result = service.list(admin, CancelApprovalStatus.REQUESTED);

        assertThat(result).containsExactly(a);
    }

    @Test
    @DisplayName("list: MERCHANT → 자기 가맹점 payment의 approval만 필터")
    void list_merchant_filters_by_merchant() {
        AuthenticatedUser merchant = new AuthenticatedUser("1", "MERCHANT", "1");
        CancelApproval mine = CancelApproval.request(1L, "pay_mine", 1L, "사유A");
        CancelApproval other = CancelApproval.request(2L, "pay_other", 1L, "사유B");
        when(cancelApprovalRepository.findByStatus(CancelApprovalStatus.REQUESTED)).thenReturn(List.of(mine, other));
        when(paymentRepository.findById(1L)).thenReturn(Optional.of(payment)); // merchantId=1
        Payment otherMerchantPayment = Payment.of("pay_other", 2L, 1L, "TOSS",
            BigDecimal.valueOf(10_000), "KRW", 90);
        when(paymentRepository.findById(2L)).thenReturn(Optional.of(otherMerchantPayment));

        List<CancelApproval> result = service.list(merchant, CancelApprovalStatus.REQUESTED);

        assertThat(result).containsExactly(mine);
    }

    @Test
    @DisplayName("list: USER → 인가 예외")
    void list_user_throws() {
        AuthenticatedUser user = new AuthenticatedUser("1", "USER", null);

        assertThatThrownBy(() -> service.list(user, CancelApprovalStatus.REQUESTED))
            .isInstanceOf(CancelNotAuthorizedException.class);
    }

    // ---- approve ----

    @Test
    @DisplayName("approve: cancel()을 정확한 커맨드로 호출하고 cancelRequestId를 연결해 APPROVED 저장")
    void approve_calls_cancel_and_links_cancelRequestId() {
        AuthenticatedUser admin = new AuthenticatedUser("9", "ADMIN", null);
        CancelApproval approval = CancelApproval.request(payment.getId(), PAYMENT_KEY, payment.getUserId(), "단순 변심");
        PaymentItem item1 = PaymentItem.reconstruct(1L, payment.getId(), 10L, 100L, 200L, "상품A",
            BigDecimal.valueOf(1000), PaymentItemStatus.ACTIVE);
        PaymentItem item2 = PaymentItem.reconstruct(2L, payment.getId(), 11L, 101L, 201L, "상품B",
            BigDecimal.valueOf(2000), PaymentItemStatus.ACTIVE);

        when(cancelApprovalRepository.findById(1L)).thenReturn(Optional.of(approval));
        when(paymentRepository.findById(payment.getId())).thenReturn(Optional.of(payment));
        when(paymentItemRepository.findAllByPaymentIdOrderByIdAsc(payment.getId()))
            .thenReturn(List.of(item1, item2));

        CancelRequest cancelRequest = mock(CancelRequest.class);
        when(cancelRequest.getId()).thenReturn(555L);
        when(cancelPaymentUseCase.cancel(any(CancelPaymentCommand.class))).thenReturn(cancelRequest);
        when(cancelApprovalRepository.save(any(CancelApproval.class))).thenAnswer(inv -> inv.getArgument(0));

        CancelApproval result = service.approve(1L, admin);

        ArgumentCaptor<CancelPaymentCommand> cmdCaptor = ArgumentCaptor.forClass(CancelPaymentCommand.class);
        verify(cancelPaymentUseCase).cancel(cmdCaptor.capture());
        CancelPaymentCommand cmd = cmdCaptor.getValue();
        assertThat(cmd.paymentKey()).isEqualTo(PAYMENT_KEY);
        assertThat(cmd.cancelReason()).isEqualTo("단순 변심");
        assertThat(cmd.cancelPaymentItemIds()).containsExactly(1L, 2L);
        assertThat(cmd.idempotencyKey()).isNull();

        ArgumentCaptor<CancelApproval> savedCaptor = ArgumentCaptor.forClass(CancelApproval.class);
        verify(cancelApprovalRepository).save(savedCaptor.capture());
        assertThat(savedCaptor.getValue().getStatus()).isEqualTo(CancelApprovalStatus.APPROVED);
        assertThat(savedCaptor.getValue().getCancelRequestId()).isEqualTo(555L);
        assertThat(result.getStatus()).isEqualTo(CancelApprovalStatus.APPROVED);
    }

    @Test
    @DisplayName("approve: 승인 요청 없음 → 404 CancelApprovalNotFoundException")
    void approve_missing_approval_throws_404() {
        AuthenticatedUser admin = new AuthenticatedUser("9", "ADMIN", null);
        when(cancelApprovalRepository.findById(1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.approve(1L, admin))
            .isInstanceOf(CancelApprovalNotFoundException.class);

        verify(cancelPaymentUseCase, never()).cancel(any());
    }

    @Test
    @DisplayName("approve: 이미 결정된 요청(REQUESTED 아님) → 409, cancel() 호출 안 함")
    void approve_non_requested_throws_409() {
        AuthenticatedUser admin = new AuthenticatedUser("9", "ADMIN", null);
        CancelApproval approved = CancelApproval.request(payment.getId(), PAYMENT_KEY, payment.getUserId(), "사유");
        approved.approve(1L, "ADMIN", 111L);

        when(cancelApprovalRepository.findById(1L)).thenReturn(Optional.of(approved));
        when(paymentRepository.findById(payment.getId())).thenReturn(Optional.of(payment));

        assertThatThrownBy(() -> service.approve(1L, admin))
            .isInstanceOf(DuplicateCancelRequestException.class);

        verify(cancelPaymentUseCase, never()).cancel(any());
        verify(cancelApprovalRepository, never()).save(any());
    }

    @Test
    @DisplayName("approve: MERCHANT가 다른 가맹점 결제를 승인 시도 → 403, cancel() 호출 안 함")
    void approve_merchant_other_merchant_throws() {
        AuthenticatedUser otherMerchant = new AuthenticatedUser("2", "MERCHANT", "2"); // payment.merchantId=1
        CancelApproval approval = CancelApproval.request(payment.getId(), PAYMENT_KEY, payment.getUserId(), "사유");

        when(cancelApprovalRepository.findById(1L)).thenReturn(Optional.of(approval));
        when(paymentRepository.findById(payment.getId())).thenReturn(Optional.of(payment));

        assertThatThrownBy(() -> service.approve(1L, otherMerchant))
            .isInstanceOf(CancelNotAuthorizedException.class);

        verify(cancelPaymentUseCase, never()).cancel(any());
        verify(cancelApprovalRepository, never()).save(any());
    }

    @Test
    @DisplayName("approve: USER는 승인 불가 → 403, cancel() 호출 안 함")
    void approve_user_throws() {
        AuthenticatedUser user = new AuthenticatedUser("1", "USER", null);
        CancelApproval approval = CancelApproval.request(payment.getId(), PAYMENT_KEY, payment.getUserId(), "사유");

        when(cancelApprovalRepository.findById(1L)).thenReturn(Optional.of(approval));
        when(paymentRepository.findById(payment.getId())).thenReturn(Optional.of(payment));

        assertThatThrownBy(() -> service.approve(1L, user))
            .isInstanceOf(CancelNotAuthorizedException.class);

        verify(cancelPaymentUseCase, never()).cancel(any());
        verify(cancelApprovalRepository, never()).save(any());
    }

    // ---- reject ----

    @Test
    @DisplayName("reject: REJECTED 저장 + 사유 기록, cancel()은 절대 호출 안 함")
    void reject_sets_REJECTED_and_does_not_call_cancel() {
        AuthenticatedUser admin = new AuthenticatedUser("9", "ADMIN", null);
        CancelApproval approval = CancelApproval.request(payment.getId(), PAYMENT_KEY, payment.getUserId(), "사유");

        when(cancelApprovalRepository.findById(1L)).thenReturn(Optional.of(approval));
        when(paymentRepository.findById(payment.getId())).thenReturn(Optional.of(payment));
        when(cancelApprovalRepository.save(any(CancelApproval.class))).thenAnswer(inv -> inv.getArgument(0));

        CancelApproval result = service.reject(1L, admin, "정책상 반려");

        ArgumentCaptor<CancelApproval> captor = ArgumentCaptor.forClass(CancelApproval.class);
        verify(cancelApprovalRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(CancelApprovalStatus.REJECTED);
        assertThat(captor.getValue().getDecisionReason()).isEqualTo("정책상 반려");
        assertThat(result.getStatus()).isEqualTo(CancelApprovalStatus.REJECTED);

        verify(cancelPaymentUseCase, never()).cancel(any());
    }

    @Test
    @DisplayName("reject: 승인 요청 없음 → 404")
    void reject_missing_approval_throws_404() {
        AuthenticatedUser admin = new AuthenticatedUser("9", "ADMIN", null);
        when(cancelApprovalRepository.findById(1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.reject(1L, admin, "사유"))
            .isInstanceOf(CancelApprovalNotFoundException.class);

        verify(cancelPaymentUseCase, never()).cancel(any());
    }
}
