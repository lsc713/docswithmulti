package com.example.payment.application.service;

import com.example.payment.application.authz.ApprovalAuthorizer;
import com.example.payment.application.authz.AuthenticatedUser;
import com.example.payment.application.exception.PaymentNotFoundException;
import com.example.payment.application.interfaces.CancelApprovalRepository;
import com.example.payment.application.interfaces.PaymentItemRepository;
import com.example.payment.application.interfaces.PaymentRepository;
import com.example.payment.application.usecase.CancelApprovalUseCase;
import com.example.payment.application.usecase.CancelPaymentUseCase;
import com.example.payment.common.exception.domain.CancelApprovalNotFoundException;
import com.example.payment.common.exception.domain.CancelNotAuthorizedException;
import com.example.payment.common.exception.domain.DuplicateCancelRequestException;
import com.example.payment.domain.entity.CancelApproval;
import com.example.payment.domain.entity.CancelApprovalStatus;
import com.example.payment.domain.entity.CancelRequest;
import com.example.payment.domain.entity.Payment;
import com.example.payment.domain.entity.PaymentItem;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 취소 승인 워크플로우 오케스트레이션 (요청/조회/승인/반려).
 *
 * <p><b>취소 코어 불변</b>: approve()는 기존 {@link CancelPaymentUseCase#cancel} 을 그대로 호출하는
 * 새 호출자일 뿐이다. cancel()이 스스로 TX1/TX2/TX3 경계를 소유하므로 이 서비스는 외부 @Transactional로
 * 감싸지 않는다 — 승인 레코드 저장은 cancel() 리턴 이후 별도로 실행한다.
 */
@Service
@RequiredArgsConstructor
public class CancelApprovalService implements CancelApprovalUseCase {

    private final CancelApprovalRepository cancelApprovalRepository;
    private final PaymentRepository paymentRepository;
    private final PaymentItemRepository paymentItemRepository;
    private final CancelPaymentUseCase cancelPaymentUseCase;
    private final ApprovalAuthorizer approvalAuthorizer = new ApprovalAuthorizer();

    @Override
    public CancelApproval request(String paymentKey, AuthenticatedUser user, String reason) {
        Payment payment = paymentRepository.findByPaymentKey(paymentKey)
            .orElseThrow(() -> new PaymentNotFoundException(paymentKey));

        approvalAuthorizer.authorizeRequest(user, payment.getUserId());

        if (cancelApprovalRepository.findActiveRequestedByPaymentId(payment.getId()).isPresent()) {
            throw new DuplicateCancelRequestException("이미 진행 중인 취소 승인 요청이 있습니다: " + paymentKey);
        }

        return cancelApprovalRepository.save(
            CancelApproval.request(payment.getId(), paymentKey, payment.getUserId(), reason));
    }

    @Override
    public List<CancelApproval> list(AuthenticatedUser user, CancelApprovalStatus status) {
        String role = user.role();
        if ("ADMIN".equals(role)) {
            return cancelApprovalRepository.findByStatus(status);
        }
        if ("MERCHANT".equals(role)) {
            long merchantId = parseLong(user.merchantId());
            return cancelApprovalRepository.findByStatus(status).stream()
                .filter(approval -> matchesMerchant(approval, merchantId))
                .toList();
        }
        throw new CancelNotAuthorizedException();
    }

    @Override
    public CancelApproval approve(long approvalId, AuthenticatedUser user) {
        CancelApproval approval = findApprovalOrThrow(approvalId);
        Payment payment = findPaymentOrThrow(approval);

        approvalAuthorizer.authorizeDecision(user, payment.getMerchantId());
        requireRequested(approval);

        List<Long> itemIds = paymentItemRepository.findAllByPaymentIdOrderByIdAsc(payment.getId()).stream()
            .map(PaymentItem::getId)
            .toList();

        CancelRequest cancelRequest = cancelPaymentUseCase.cancel(
            new CancelPaymentCommand(payment.getPaymentKey(), approval.getReason(), itemIds, null));

        approval.approve(parseLong(user.userId()), user.role(), cancelRequest.getId());
        return cancelApprovalRepository.save(approval);
    }

    @Override
    public CancelApproval reject(long approvalId, AuthenticatedUser user, String decisionReason) {
        CancelApproval approval = findApprovalOrThrow(approvalId);
        Payment payment = findPaymentOrThrow(approval);

        approvalAuthorizer.authorizeDecision(user, payment.getMerchantId());
        requireRequested(approval);

        approval.reject(parseLong(user.userId()), user.role(), decisionReason);
        return cancelApprovalRepository.save(approval);
    }

    private CancelApproval findApprovalOrThrow(long approvalId) {
        return cancelApprovalRepository.findById(approvalId)
            .orElseThrow(() -> new CancelApprovalNotFoundException(approvalId));
    }

    private Payment findPaymentOrThrow(CancelApproval approval) {
        return paymentRepository.findById(approval.getPaymentId())
            .orElseThrow(() -> new PaymentNotFoundException(approval.getPaymentKey()));
    }

    private void requireRequested(CancelApproval approval) {
        if (approval.getStatus() != CancelApprovalStatus.REQUESTED) {
            throw new DuplicateCancelRequestException("이미 결정된 승인 요청입니다: " + approval.getId());
        }
    }

    private boolean matchesMerchant(CancelApproval approval, long merchantId) {
        return paymentRepository.findById(approval.getPaymentId())
            .map(payment -> payment.getMerchantId() == merchantId)
            .orElse(false);
    }

    /** null/malformed-safe: 실패 시 -1(실사용 id와 절대 일치하지 않는 sentinel) — CancelAuthorizationService와 동일 idiom. */
    private long parseLong(String raw) {
        if (raw == null) {
            return -1L;
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            return -1L;
        }
    }
}
