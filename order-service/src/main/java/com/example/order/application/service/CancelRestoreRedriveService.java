package com.example.order.application.service;

import com.example.order.application.interfaces.CancelRestoreDlqRepository;
import com.example.order.application.interfaces.OperationAlertPort;
import com.example.order.application.usecase.ProcessCancelledItemsUseCase;
import com.example.order.application.usecase.ProcessCancelledItemsUseCase.Command;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * cancel_restore_dlq PENDING 재구동 (Phase 1 product CancelRestoreRedriveService 미러). 스케줄러가 파싱해 넘긴 Command 로 핸들러 재호출.
 *
 * <p>핸들러({@link ProcessCancelledItemsUseCase})는 processed_cancel_event 멱등 게이트가 있어
 * 이미 처리분은 no-op → 재구동이 주문 상태를 과다 변경하지 않는다.
 * 성공 → RESOLVED. 실패 → attempt_count+1&gt;=임계(기본5)면 DEAD+에스컬레이션, 아니면 bumpAttempt.
 */
@Slf4j
@Service
public class CancelRestoreRedriveService {

    private static final String LEG = "ORDER";

    private final ProcessCancelledItemsUseCase processUseCase;
    private final CancelRestoreDlqRepository dlqRepository;
    private final OperationAlertPort operationAlertPort;

    /** DEAD 전이 임계 (§8 확정값 5). attempt_count+1 이 이 값 이상이면 영구 실패로 격리. */
    @Value("${cancel-restore.redrive.dead-threshold:5}")
    private int deadThreshold;

    public CancelRestoreRedriveService(ProcessCancelledItemsUseCase processUseCase,
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
            int attempts = attemptCount + 1;
            if (attempts >= deadThreshold) {
                // REDRIVE-02: 임계 초과 → DEAD 격리 + 에스컬레이션 (Phase 1 product 미러).
                dlqRepository.markDead(cancelRequestId, err);
                operationAlertPort.alert("[cancel-restore][" + LEG + "] 재구동 영구 실패(DEAD) cancelRequestId="
                    + cancelRequestId + " attempts=" + attempts + " err=" + err);
                log.error("[cancel-restore-redrive] DEAD 전이 cancelRequestId={} attempts={}", cancelRequestId, attempts, e);
            } else {
                dlqRepository.bumpAttempt(cancelRequestId, err);
                log.warn("[cancel-restore-redrive] 재구동 실패 bumpAttempt cancelRequestId={} attempts={} err={}",
                    cancelRequestId, attempts, err);
            }
        }
    }

    static String truncate(String s) {
        if (s == null) return "unknown";
        return s.length() <= 512 ? s : s.substring(0, 512);
    }
}
