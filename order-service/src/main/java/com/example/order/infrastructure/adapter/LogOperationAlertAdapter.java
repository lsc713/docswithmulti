package com.example.order.infrastructure.adapter;

import com.example.order.application.interfaces.OperationAlertPort;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/** OperationAlertPort 기본 구현 (Phase 1 product LogOperationAlertAdapter 미러). 실제 채널은 배포 관심사. */
@Slf4j
@Component
public class LogOperationAlertAdapter implements OperationAlertPort {

    @Override
    public void alert(String message) {
        log.error("[ALERT] {}", message);
    }
}
