package com.example.payment.application.usecase;

import com.example.payment.application.authz.AuthenticatedUser;
import com.example.payment.domain.entity.CancelApproval;
import com.example.payment.domain.entity.CancelApprovalStatus;
import java.util.List;

/**
 * 취소 승인 워크플로우 (요청/조회/승인/반려).
 *
 * approve()는 기존 {@link CancelPaymentUseCase#cancel} 을 그대로 호출한다 — 취소 코어(TX1/TX2/TX3,
 * 멱등, 스케줄러, outbox) 는 이 유스케이스의 새 호출자일 뿐 무변경.
 */
public interface CancelApprovalUseCase {

    CancelApproval request(String paymentKey, AuthenticatedUser user, String reason);

    List<CancelApproval> list(AuthenticatedUser user, CancelApprovalStatus status);

    CancelApproval approve(long approvalId, AuthenticatedUser user);

    CancelApproval reject(long approvalId, AuthenticatedUser user, String decisionReason);
}
