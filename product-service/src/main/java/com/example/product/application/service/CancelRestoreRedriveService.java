package com.example.product.application.service;

import com.example.product.application.interfaces.CancelRestoreDlqRepository;
import com.example.product.application.interfaces.OperationAlertPort;
import com.example.product.application.usecase.ProcessCancelledStockUseCase;
import com.example.product.application.usecase.ProcessCancelledStockUseCase.Command;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * cancel_restore_dlq PENDING 재구동 (설계 §수정3). 스케줄러가 파싱해 넘긴 Command 로 핸들러 재호출.
 *
 * <p>핸들러({@link ProcessCancelledStockUseCase})는 processed_cancel_event 멱등 게이트가 있어
 * 이미 처리분은 no-op → 재구동이 재고를 과다 복원하지 않는다.
 * 성공 → RESOLVED. 실패 → bumpAttempt(임계 판정/DEAD 전이는 Task 3에서 추가).
 */
@Slf4j
@Service
public class CancelRestoreRedriveService {

    private final ProcessCancelledStockUseCase processUseCase;
    private final CancelRestoreDlqRepository dlqRepository;
    private final OperationAlertPort operationAlertPort;

    public CancelRestoreRedriveService(ProcessCancelledStockUseCase processUseCase,
                                       CancelRestoreDlqRepository dlqRepository,
                                       OperationAlertPort operationAlertPort) {
        this.processUseCase = processUseCase;
        this.dlqRepository = dlqRepository;
        this.operationAlertPort = operationAlertPort;
    }

    public void redriveOne(String cancelRequestId, int attemptCount, Command command) {
        try {
            processUseCase.execute(command);
            dlqRepository.markResolved(cancelRequestId);
            log.info("[cancel-restore-redrive] 재구동 성공 RESOLVED cancelRequestId={}", cancelRequestId);
        } catch (Exception e) {
            String err = truncate(e.getMessage());
            dlqRepository.bumpAttempt(cancelRequestId, err);
            log.warn("[cancel-restore-redrive] 재구동 실패 bumpAttempt cancelRequestId={} attempts={} err={}",
                cancelRequestId, attemptCount + 1, err);
        }
    }

    static String truncate(String s) {
        if (s == null) return "unknown";
        return s.length() <= 512 ? s : s.substring(0, 512);
    }
}
