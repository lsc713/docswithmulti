package com.example.payment.application.service;

import com.example.payment.application.exception.CancelOutboxNotFoundException;
import com.example.payment.application.exception.InvalidCancelEventPayloadException;
import com.example.payment.application.interfaces.CancelEventPayloadParser;
import com.example.payment.application.interfaces.CancelOutboxSourcePort;
import com.example.payment.application.interfaces.OrderCancelStatusPort;
import com.example.payment.application.interfaces.StockRestoreStatusPort;
import com.example.payment.application.model.CancelEventPayload;
import com.example.payment.application.model.CancelOutboxDecision;
import com.example.payment.application.model.CancelOutboxReasonCode;
import com.example.payment.application.model.CancelRestoreLegSnapshot;
import com.example.payment.application.model.CancelRestoreLegStatus;
import com.example.payment.application.usecase.CancelOutboxInspectionUseCase;
import com.example.payment.domain.entity.CancelStatus;
import com.example.payment.domain.entity.PaymentStatus;
import org.springframework.stereotype.Service;

import java.util.Set;

@Service
public class CancelOutboxInspectionService implements CancelOutboxInspectionUseCase {

    private static final Set<PaymentStatus> CANCELLED_PAYMENT_STATUSES = Set.of(
        PaymentStatus.CANCELLED, PaymentStatus.PARTIAL_CANCELLED);

    private final CancelOutboxSourcePort sourcePort;
    private final CancelEventPayloadParser payloadParser;
    private final OrderCancelStatusPort orderStatusPort;
    private final StockRestoreStatusPort stockStatusPort;

    public CancelOutboxInspectionService(
        CancelOutboxSourcePort sourcePort,
        CancelEventPayloadParser payloadParser,
        OrderCancelStatusPort orderStatusPort,
        StockRestoreStatusPort stockStatusPort
    ) {
        this.sourcePort = sourcePort;
        this.payloadParser = payloadParser;
        this.orderStatusPort = orderStatusPort;
        this.stockStatusPort = stockStatusPort;
    }

    @Override
    public Result inspect(long outboxId) {
        var source = sourcePort.findById(outboxId)
            .orElseThrow(() -> new CancelOutboxNotFoundException(outboxId));

        if (!"DEAD".equals(source.outboxStatus())) {
            return terminal(source, CancelOutboxDecision.NOT_ELIGIBLE,
                CancelOutboxReasonCode.OUTBOX_NOT_DEAD);
        }
        if (source.cancelStatus() != CancelStatus.COMPLETED) {
            return terminal(source, CancelOutboxDecision.NOT_ELIGIBLE,
                CancelOutboxReasonCode.CANCEL_NOT_COMPLETED);
        }
        if (!CANCELLED_PAYMENT_STATUSES.contains(source.paymentStatus())) {
            return terminal(source, CancelOutboxDecision.NOT_ELIGIBLE,
                CancelOutboxReasonCode.PAYMENT_NOT_CANCELLED);
        }

        final CancelEventPayload payload;
        try {
            payload = payloadParser.parse(source.payload());
        } catch (InvalidCancelEventPayloadException e) {
            return terminal(source, CancelOutboxDecision.NOT_ELIGIBLE,
                CancelOutboxReasonCode.INVALID_PAYLOAD);
        }
        if (payload.cancelRequestId() != source.cancelRequestId()) {
            return terminal(source, CancelOutboxDecision.NOT_ELIGIBLE,
                CancelOutboxReasonCode.INVALID_PAYLOAD);
        }

        var order = orderStatusPort.inspect(new OrderCancelStatusPort.Command(
            String.valueOf(source.cancelRequestId()),
            payload.items().stream().map(CancelEventPayload.Item::orderItemId).toList()));
        var stock = stockStatusPort.inspect(new StockRestoreStatusPort.Command(
            String.valueOf(source.cancelRequestId()),
            payload.paymentKey(),
            payload.items().stream()
                .map(item -> new StockRestoreStatusPort.Item(item.skuId(), item.quantity()))
                .toList()));

        if (hasStatus(order, stock, CancelRestoreLegStatus.UNKNOWN)) {
            return result(source, CancelOutboxDecision.UNKNOWN,
                CancelOutboxReasonCode.DOWNSTREAM_UNKNOWN, order, stock);
        }
        if (hasStatus(order, stock, CancelRestoreLegStatus.INCONSISTENT)) {
            return result(source, CancelOutboxDecision.NOT_ELIGIBLE,
                CancelOutboxReasonCode.INCONSISTENT_DOWNSTREAM_STATE, order, stock);
        }
        if (order.status() == CancelRestoreLegStatus.APPLIED
            && stock.status() == CancelRestoreLegStatus.APPLIED) {
            return result(source, CancelOutboxDecision.ALREADY_APPLIED, null, order, stock);
        }
        return result(source, CancelOutboxDecision.REDRIVE_REQUIRED, null, order, stock);
    }

    private boolean hasStatus(
        CancelRestoreLegSnapshot order,
        CancelRestoreLegSnapshot stock,
        CancelRestoreLegStatus expected
    ) {
        return order.status() == expected || stock.status() == expected;
    }

    private Result terminal(
        CancelOutboxSourcePort.SourceSnapshot source,
        CancelOutboxDecision decision,
        CancelOutboxReasonCode reasonCode
    ) {
        return result(source, decision, reasonCode, null, null);
    }

    private Result result(
        CancelOutboxSourcePort.SourceSnapshot source,
        CancelOutboxDecision decision,
        CancelOutboxReasonCode reasonCode,
        CancelRestoreLegSnapshot order,
        CancelRestoreLegSnapshot stock
    ) {
        return new Result(source.outboxId(), source.cancelRequestId(),
            decision, reasonCode, order, stock);
    }
}
