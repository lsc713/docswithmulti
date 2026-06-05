package com.example.product.infrastructure.persistence;

import com.example.product.domain.entity.ProcessedStockEvent;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "processed_stock_event")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProcessedStockEventJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private long cancelRequestId;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    private ProcessedStockEventJpaEntity(Long id, long cancelRequestId, LocalDateTime createdAt) {
        this.id = id;
        this.cancelRequestId = cancelRequestId;
        this.createdAt = createdAt;
    }

    public static ProcessedStockEventJpaEntity from(ProcessedStockEvent event) {
        return new ProcessedStockEventJpaEntity(
                event.getId(),
                event.getCancelRequestId(),
                event.getCreatedAt()
        );
    }

    public ProcessedStockEvent toDomain() {
        return ProcessedStockEvent.reconstruct(id, cancelRequestId, createdAt);
    }
}
