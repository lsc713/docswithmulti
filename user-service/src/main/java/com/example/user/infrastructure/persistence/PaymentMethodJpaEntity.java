package com.example.user.infrastructure.persistence;

import com.example.user.domain.entity.PaymentMethod;
import com.example.user.domain.entity.PaymentMethodType;
import jakarta.persistence.*;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

@Entity
@Table(name = "payment_methods")
public class PaymentMethodJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    private PaymentMethodType type;

    @Column(name = "card_number", length = 20)
    private String cardNumber;

    @Column(name = "card_company", length = 50)
    private String cardCompany;

    @Column(name = "bank_name", length = 50)
    private String bankName;

    @Column(name = "account_number", length = 30)
    private String accountNumber;

    @Column(name = "is_default", nullable = false)
    private boolean isDefault;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    protected PaymentMethodJpaEntity() {}

    public static PaymentMethodJpaEntity from(PaymentMethod pm) {
        PaymentMethodJpaEntity entity = new PaymentMethodJpaEntity();
        entity.id = pm.getId();
        entity.userId = pm.getUserId();
        entity.type = pm.getType();
        entity.cardNumber = pm.getCardNumber();
        entity.cardCompany = pm.getCardCompany();
        entity.bankName = pm.getBankName();
        entity.accountNumber = pm.getAccountNumber();
        entity.isDefault = pm.isDefault();
        entity.createdAt = LocalDateTime.ofInstant(pm.getCreatedAt(), ZoneOffset.UTC);
        entity.updatedAt = LocalDateTime.ofInstant(pm.getUpdatedAt(), ZoneOffset.UTC);
        return entity;
    }

    public PaymentMethod toDomain() {
        return PaymentMethod.reconstruct(
                id, userId, type, cardNumber, cardCompany, bankName, accountNumber, isDefault,
                createdAt.toInstant(ZoneOffset.UTC),
                updatedAt.toInstant(ZoneOffset.UTC)
        );
    }
}
