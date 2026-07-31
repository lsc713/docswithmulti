package com.example.product.application.service;

import com.example.product.application.interfaces.ProcessedCancelEventRepository;
import com.example.product.application.usecase.ProcessCancelledStockUseCase;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

/**
 * 취소 이벤트 → SKU 재고 복원 (RST-02).
 *
 * <p>Phase 1 의 원자 상태전이 {@link StockService#release}를 재사용해 위임한다.
 * release 는 releaseIfReserved 조건부 전이(affected=1 일 때만 복원)로 over-release 불가·멱등.
 *
 * <p><b>멱등 게이트(D-P3-2/D-P3-4, order ProcessCancelledItemsService 동형)</b>: 단일 TX 안에서
 * cancelRequestId 를 processed_cancel_event UK 로 선체크 → 이미 처리면 즉시 no-op(release 재호출 자체를
 * 건너뜀), 아니면 release 후 processed_cancel_event 적재. at-least-once 중복 이벤트가 재고를 과다 복원하지
 * 않도록 상위 게이트로 차단(release W2 멱등과 이중 안전).
 *
 * <p><b>부분취소</b>: command.items 는 취소된 SKU(cancelledItems)만 담고 있으므로 release 가 그 SKU 만
 * 처리 — 나머지 예약은 RESERVED 로 유지된다(별도 로직 불필요).
 */
@Service
public class ProcessCancelledStockService implements ProcessCancelledStockUseCase {

    private final StockService stockService;
    private final ProcessedCancelEventRepository processedCancelEventRepository;
    private final TransactionTemplate transactionTemplate;

    public ProcessCancelledStockService(StockService stockService,
                                        ProcessedCancelEventRepository processedCancelEventRepository,
                                        TransactionTemplate transactionTemplate) {
        this.stockService = stockService;
        this.processedCancelEventRepository = processedCancelEventRepository;
        this.transactionTemplate = transactionTemplate;
    }

    @Override
    public void execute(Command command) {
        transactionTemplate.execute(status -> {
            if (processedCancelEventRepository.existsByCancelRequestId(command.cancelRequestId())) {
                return null; // 중복 이벤트 → no-op (추가 복원 없음)
            }
            List<StockService.ReserveItem> items = command.items().stream()
                .map(i -> new StockService.ReserveItem(i.skuId(), i.qty()))
                .toList();
            stockService.release(command.paymentKey(), items);
            processedCancelEventRepository.save(command.cancelRequestId());
            return null;
        });
    }
}
