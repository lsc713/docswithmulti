package com.example.product.application.service;

import com.example.product.application.interfaces.ProcessedCancelEventRepository;
import com.example.product.application.interfaces.StockReservationRepository;
import com.example.product.application.model.CancelRestoreLegStatus;
import com.example.product.application.usecase.InspectCancelRestoreUseCase.Command;
import com.example.product.application.usecase.InspectCancelRestoreUseCase.Evidence;
import com.example.product.application.usecase.InspectCancelRestoreUseCase.Item;
import com.example.product.domain.entity.ReservationStatus;
import com.example.product.domain.entity.StockReservation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class InspectCancelRestoreServiceTest {

    private ProcessedCancelEventRepository processed;
    private StockReservationRepository reservations;
    private InspectCancelRestoreService service;

    @BeforeEach
    void setUp() {
        processed = mock(ProcessedCancelEventRepository.class);
        reservations = mock(StockReservationRepository.class);
        service = new InspectCancelRestoreService(processed, reservations);
    }

    @Test
    void processedMarkerAndReleasedReservationsAreApplied() {
        when(processed.existsByCancelRequestId("27")).thenReturn(true);
        when(reservations.findByPaymentKeyAndSkuId("pay_1", 8L))
            .thenReturn(Optional.of(reservation(8L, 2, ReservationStatus.RELEASED)));

        var result = service.inspect(command(new Item(8L, 2)));

        assertThat(result.status()).isEqualTo(CancelRestoreLegStatus.APPLIED);
        assertThat(result.evidence()).isEmpty();
    }

    @Test
    void noMarkerAndReservedReservationsAreNotApplied() {
        when(processed.existsByCancelRequestId("27")).thenReturn(false);
        when(reservations.findByPaymentKeyAndSkuId("pay_1", 8L))
            .thenReturn(Optional.of(reservation(8L, 2, ReservationStatus.RESERVED)));

        var result = service.inspect(command(new Item(8L, 2)));

        assertThat(result.status()).isEqualTo(CancelRestoreLegStatus.NOT_APPLIED);
        assertThat(result.evidence()).isEmpty();
    }

    @Test
    void processedMarkerWithReservedReservationIsInconsistent() {
        when(processed.existsByCancelRequestId("27")).thenReturn(true);
        when(reservations.findByPaymentKeyAndSkuId("pay_1", 8L))
            .thenReturn(Optional.of(reservation(8L, 2, ReservationStatus.RESERVED)));

        var result = service.inspect(command(new Item(8L, 2)));

        assertThat(result.status()).isEqualTo(CancelRestoreLegStatus.INCONSISTENT);
        assertThat(result.evidence()).containsExactly(
            new Evidence(8L, "RESERVED", 2, 2));
    }

    @Test
    void releasedReservationWithoutMarkerIsInconsistent() {
        when(processed.existsByCancelRequestId("27")).thenReturn(false);
        when(reservations.findByPaymentKeyAndSkuId("pay_1", 8L))
            .thenReturn(Optional.of(reservation(8L, 2, ReservationStatus.RELEASED)));

        var result = service.inspect(command(new Item(8L, 2)));

        assertThat(result.status()).isEqualTo(CancelRestoreLegStatus.INCONSISTENT);
        assertThat(result.evidence()).containsExactly(
            new Evidence(8L, "RELEASED", 2, 2));
    }

    @Test
    void quantityMismatchIsInconsistent() {
        when(processed.existsByCancelRequestId("27")).thenReturn(true);
        when(reservations.findByPaymentKeyAndSkuId("pay_1", 8L))
            .thenReturn(Optional.of(reservation(8L, 1, ReservationStatus.RELEASED)));

        var result = service.inspect(command(new Item(8L, 2)));

        assertThat(result.status()).isEqualTo(CancelRestoreLegStatus.INCONSISTENT);
        assertThat(result.evidence()).containsExactly(
            new Evidence(8L, "RELEASED", 1, 2));
    }

    @Test
    void missingReservationIsInconsistent() {
        when(processed.existsByCancelRequestId("27")).thenReturn(true);
        when(reservations.findByPaymentKeyAndSkuId("pay_1", 8L))
            .thenReturn(Optional.empty());

        var result = service.inspect(command(new Item(8L, 2)));

        assertThat(result.status()).isEqualTo(CancelRestoreLegStatus.INCONSISTENT);
        assertThat(result.evidence()).containsExactly(
            new Evidence(8L, "MISSING", null, 2));
    }

    @Test
    void duplicateSkuTargetsAreInconsistent() {
        var result = service.inspect(new Command(
            "27", "pay_1", List.of(new Item(8L, 2), new Item(8L, 2))));

        assertThat(result.status()).isEqualTo(CancelRestoreLegStatus.INCONSISTENT);
        assertThat(result.evidence()).containsExactly(
            new Evidence(8L, "DUPLICATE", null, 2));
    }

    private static Command command(Item item) {
        return new Command("27", "pay_1", List.of(item));
    }

    private static StockReservation reservation(
        long skuId, int quantity, ReservationStatus status
    ) {
        Instant now = Instant.parse("2026-08-10T00:00:00Z");
        return StockReservation.reconstruct(
            1L, "pay_1", skuId, quantity, status, now, now);
    }
}
