package com.example.payment.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

import com.example.payment.application.interfaces.CancelHistoryEntry;
import com.example.payment.domain.entity.CancelStatus;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CancelRequestHistoryRepositoryImplTest {

    @Mock CancelRequestHistoryJpaRepository jpaRepository;
    @InjectMocks CancelRequestHistoryRepositoryImpl sut;
    @Captor ArgumentCaptor<List<CancelRequestHistoryJpaEntity>> captor;

    @Test
    void recordAll_maps_entries_preserving_occurredAt_and_saves_once() {
        Instant t1 = Instant.parse("2026-07-11T00:00:01Z");
        Instant t2 = Instant.parse("2026-07-11T00:00:02Z");
        sut.recordAll(List.of(
            new CancelHistoryEntry(7L, CancelStatus.PENDING, null, t1),
            new CancelHistoryEntry(7L, CancelStatus.COMPLETED, "done", t2)
        ));

        verify(jpaRepository).saveAll(captor.capture());
        List<CancelRequestHistoryJpaEntity> rows = captor.getValue();
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).getStatus()).isEqualTo("PENDING");
        assertThat(rows.get(0).getCreatedAt()).isEqualTo(t1);
        assertThat(rows.get(1).getStatus()).isEqualTo("COMPLETED");
        assertThat(rows.get(1).getReason()).isEqualTo("done");
        assertThat(rows.get(1).getCreatedAt()).isEqualTo(t2);
    }
}
