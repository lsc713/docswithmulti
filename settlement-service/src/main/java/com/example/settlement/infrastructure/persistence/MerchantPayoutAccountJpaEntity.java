package com.example.settlement.infrastructure.persistence;

import com.example.settlement.domain.entity.MerchantPayoutAccount;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * merchant_payout_account JPA 매핑. 쓰기는 native upsert 로 처리하므로 read-mostly.
 * 컬럼명/타입은 V2 DDL과 정확히 일치해야 함(ddl-auto=validate) —
 * merchant_id PK(client 지정, @GeneratedValue 없음), bank_code(10)/account_number(64)/holder_name(100), active, timestamps.
 */
@Entity
@Table(name = "merchant_payout_account")
public class MerchantPayoutAccountJpaEntity {

    @Id
    @Column(name = "merchant_id", nullable = false)
    private Long merchantId;

    @Column(name = "bank_code", nullable = false, length = 10)
    private String bankCode;

    @Column(name = "account_number", nullable = false, length = 64)
    private String accountNumber;

    @Column(name = "holder_name", nullable = false, length = 100)
    private String holderName;

    @Column(name = "active", nullable = false)
    private Boolean active;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected MerchantPayoutAccountJpaEntity() {}

    public MerchantPayoutAccount toDomain() {
        return MerchantPayoutAccount.reconstruct(merchantId, bankCode, accountNumber, holderName,
            active, createdAt, updatedAt);
    }
}
