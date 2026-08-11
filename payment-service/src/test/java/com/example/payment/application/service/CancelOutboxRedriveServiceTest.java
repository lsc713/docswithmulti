package com.example.payment.application.service;

import com.example.payment.application.exception.CancelOutboxRedriveNotFoundException;
import com.example.payment.application.exception.InvalidRedriveReasonException;
import com.example.payment.application.interfaces.CancelOutboxRedriveRepository;
import com.example.payment.domain.entity.CancelOutboxRedrive;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class CancelOutboxRedriveServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-11T00:00:00Z");

    private CancelOutboxRedriveRepository repository;
    private CancelOutboxRedriveService service;

    @BeforeEach
    void setUp() {
        repository = mock(CancelOutboxRedriveRepository.class);
        service = new CancelOutboxRedriveService(repository, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", " ", "\t\n"})
    void invalidReasonCreatesNoRow(String reason) {
        assertThatThrownBy(() -> service.request(41L, "operator-1", reason))
            .isInstanceOf(InvalidRedriveReasonException.class);

        verifyNoInteractions(repository);
    }

    @Test
    void reasonOverFiveHundredCodePointsCreatesNoRow() {
        String reason = "😀".repeat(501);

        assertThatThrownBy(() -> service.request(41L, "operator-1", reason))
            .isInstanceOf(InvalidRedriveReasonException.class);

        verifyNoInteractions(repository);
    }

    @Test
    void validReasonUsesCodePointLengthAndPreservesOriginalText() {
        String reason = "😀".repeat(499) + " ";
        when(repository.createRequested(41L, "operator-1", reason, NOW))
            .thenReturn(CancelOutboxRedrive.requested(41L, "operator-1", reason, NOW));

        var result = service.request(41L, "operator-1", reason);

        assertThat(result.getReason()).isEqualTo(reason);
        verify(repository).createRequested(41L, "operator-1", reason, NOW);
    }

    @Test
    void missingRedriveThrowsNotFoundException() {
        when(repository.findById(404L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(404L))
            .isInstanceOf(CancelOutboxRedriveNotFoundException.class);
    }

    @Test
    void existingRedriveIsReturned() {
        CancelOutboxRedrive redrive = CancelOutboxRedrive.requested(41L, "operator-1", "reason", NOW);
        when(repository.findById(7L)).thenReturn(Optional.of(redrive));

        assertThat(service.get(7L)).isSameAs(redrive);
        verify(repository).findById(7L);
    }
}
