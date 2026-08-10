package com.example.payment.application.service;

import com.example.payment.application.exception.CancelOutboxNotFoundException;
import com.example.payment.application.interfaces.CancelEventPayloadParser;
import com.example.payment.application.interfaces.CancelOutboxSourcePort;
import com.example.payment.application.interfaces.OrderCancelStatusPort;
import com.example.payment.application.interfaces.StockRestoreStatusPort;
import com.example.payment.application.model.CancelEventPayload;
import com.example.payment.application.model.CancelOutboxDecision;
import com.example.payment.application.model.CancelOutboxReasonCode;
import com.example.payment.application.model.CancelRestoreLegSnapshot;
import com.example.payment.application.model.CancelRestoreLegStatus;
import com.example.payment.domain.entity.CancelStatus;
import com.example.payment.domain.entity.PaymentStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class CancelOutboxInspectionServiceTest {

    private CancelOutboxSourcePort source;
    private CancelEventPayloadParser parser;
    private OrderCancelStatusPort order;
    private StockRestoreStatusPort stock;
    private CancelOutboxInspectionService service;

    @BeforeEach
    void setUp() {
        source = mock(CancelOutboxSourcePort.class);
        parser = mock(CancelEventPayloadParser.class);
        order = mock(OrderCancelStatusPort.class);
        stock = mock(StockRestoreStatusPort.class);
        service = new CancelOutboxInspectionService(source, parser, order, stock);
    }

    @Test
    void oneNotAppliedLegRequiresRedrive() {
        givenEligibleDeadSource();
        when(order.inspect(any())).thenReturn(leg(CancelRestoreLegStatus.APPLIED));
        when(stock.inspect(any())).thenReturn(leg(CancelRestoreLegStatus.NOT_APPLIED));

        var result = service.inspect(6L);

        assertThat(result.decision()).isEqualTo(CancelOutboxDecision.REDRIVE_REQUIRED);
        assertThat(result.reasonCode()).isNull();
        assertThat(result.order().status()).isEqualTo(CancelRestoreLegStatus.APPLIED);
        assertThat(result.stock().status()).isEqualTo(CancelRestoreLegStatus.NOT_APPLIED);
    }

    @Test
    void bothAppliedMeansAlreadyApplied() {
        givenEligibleDeadSource();
        when(order.inspect(any())).thenReturn(leg(CancelRestoreLegStatus.APPLIED));
        when(stock.inspect(any())).thenReturn(leg(CancelRestoreLegStatus.APPLIED));

        assertThat(service.inspect(6L).decision())
            .isEqualTo(CancelOutboxDecision.ALREADY_APPLIED);
    }

    @Test
    void unknownLegMakesWholeDecisionUnknown() {
        givenEligibleDeadSource();
        when(order.inspect(any())).thenReturn(leg(CancelRestoreLegStatus.UNKNOWN));
        when(stock.inspect(any())).thenReturn(leg(CancelRestoreLegStatus.APPLIED));

        var result = service.inspect(6L);

        assertThat(result.decision()).isEqualTo(CancelOutboxDecision.UNKNOWN);
        assertThat(result.reasonCode()).isEqualTo(CancelOutboxReasonCode.DOWNSTREAM_UNKNOWN);
    }

    @Test
    void inconsistentLegIsNotEligible() {
        givenEligibleDeadSource();
        when(order.inspect(any())).thenReturn(leg(CancelRestoreLegStatus.INCONSISTENT));
        when(stock.inspect(any())).thenReturn(leg(CancelRestoreLegStatus.APPLIED));

        var result = service.inspect(6L);

        assertThat(result.decision()).isEqualTo(CancelOutboxDecision.NOT_ELIGIBLE);
        assertThat(result.reasonCode())
            .isEqualTo(CancelOutboxReasonCode.INCONSISTENT_DOWNSTREAM_STATE);
    }

    @Test
    void nonDeadOutboxShortCircuitsDownstream() {
        givenSource("PUBLISHED", CancelStatus.COMPLETED, PaymentStatus.CANCELLED, "payload");

        var result = service.inspect(6L);

        assertThat(result.decision()).isEqualTo(CancelOutboxDecision.NOT_ELIGIBLE);
        assertThat(result.reasonCode()).isEqualTo(CancelOutboxReasonCode.OUTBOX_NOT_DEAD);
        verifyNoInteractions(parser, order, stock);
    }

    @Test
    void incompleteCancelShortCircuitsDownstream() {
        givenSource("DEAD", CancelStatus.PROCESSING, PaymentStatus.CANCELLED, "payload");
        assertThat(service.inspect(6L).reasonCode())
            .isEqualTo(CancelOutboxReasonCode.CANCEL_NOT_COMPLETED);
        verifyNoInteractions(parser, order, stock);
    }

    @Test
    void activePaymentShortCircuitsDownstream() {
        givenSource("DEAD", CancelStatus.COMPLETED, PaymentStatus.COMPLETED, "payload");
        assertThat(service.inspect(6L).reasonCode())
            .isEqualTo(CancelOutboxReasonCode.PAYMENT_NOT_CANCELLED);
        verifyNoInteractions(parser, order, stock);
    }

    @Test
    void invalidPayloadIsNotEligibleAndDoesNotCallDownstream() {
        givenSource("DEAD", CancelStatus.COMPLETED, PaymentStatus.CANCELLED, "invalid");
        when(parser.parse("invalid")).thenThrow(
            new com.example.payment.application.exception.InvalidCancelEventPayloadException("invalid"));

        var result = service.inspect(6L);

        assertThat(result.decision()).isEqualTo(CancelOutboxDecision.NOT_ELIGIBLE);
        assertThat(result.reasonCode()).isEqualTo(CancelOutboxReasonCode.INVALID_PAYLOAD);
        verifyNoInteractions(order, stock);
    }

    @Test
    void payloadCancelRequestMismatchIsInvalidAndDoesNotCallDownstream() {
        givenSource("DEAD", CancelStatus.COMPLETED, PaymentStatus.CANCELLED, "payload");
        when(parser.parse("payload")).thenReturn(new CancelEventPayload(
            28L, "pay_1", List.of(new CancelEventPayload.Item(10L, 8L, 2))));

        var result = service.inspect(6L);

        assertThat(result.decision()).isEqualTo(CancelOutboxDecision.NOT_ELIGIBLE);
        assertThat(result.reasonCode()).isEqualTo(CancelOutboxReasonCode.INVALID_PAYLOAD);
        verifyNoInteractions(order, stock);
    }

    @Test
    void missingOutboxThrowsStableNotFoundException() {
        when(source.findById(404L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.inspect(404L))
            .isInstanceOf(CancelOutboxNotFoundException.class);
    }

    private void givenEligibleDeadSource() {
        givenSource("DEAD", CancelStatus.COMPLETED, PaymentStatus.CANCELLED, "payload");
        when(parser.parse("payload")).thenReturn(new CancelEventPayload(
            27L, "pay_1", List.of(new CancelEventPayload.Item(10L, 8L, 2))));
    }

    private void givenSource(
        String outboxStatus,
        CancelStatus cancelStatus,
        PaymentStatus paymentStatus,
        String payload
    ) {
        when(source.findById(6L)).thenReturn(Optional.of(
            new CancelOutboxSourcePort.SourceSnapshot(
                6L, 27L, payload, outboxStatus, cancelStatus, paymentStatus)));
    }

    private static CancelRestoreLegSnapshot leg(CancelRestoreLegStatus status) {
        return new CancelRestoreLegSnapshot(status, List.of());
    }
}
