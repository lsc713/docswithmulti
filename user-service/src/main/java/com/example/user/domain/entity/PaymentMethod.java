package com.example.user.domain.entity;

import java.time.Instant;
import java.util.Objects;

public class PaymentMethod {

    private Long id;
    private Long userId;
    private PaymentMethodType type;
    private String cardNumber;
    private String cardCompany;
    private String bankName;
    private String accountNumber;
    private boolean isDefault;
    private Instant createdAt;
    private Instant updatedAt;

    private PaymentMethod() {}

    public static PaymentMethod ofCard(long userId, String cardNumber, String cardCompany, boolean isDefault) {
        PaymentMethod pm = new PaymentMethod();
        pm.userId = userId;
        pm.type = PaymentMethodType.CARD;
        pm.cardNumber = cardNumber;
        pm.cardCompany = cardCompany;
        pm.isDefault = isDefault;
        pm.createdAt = Instant.now();
        pm.updatedAt = Instant.now();
        return pm;
    }

    public static PaymentMethod ofBankTransfer(long userId, String bankName, String accountNumber, boolean isDefault) {
        PaymentMethod pm = new PaymentMethod();
        pm.userId = userId;
        pm.type = PaymentMethodType.BANK_TRANSFER;
        pm.bankName = bankName;
        pm.accountNumber = accountNumber;
        pm.isDefault = isDefault;
        pm.createdAt = Instant.now();
        pm.updatedAt = Instant.now();
        return pm;
    }

    public static PaymentMethod reconstruct(Long id, long userId, PaymentMethodType type,
                                            String cardNumber, String cardCompany,
                                            String bankName, String accountNumber,
                                            boolean isDefault, Instant createdAt, Instant updatedAt) {
        PaymentMethod pm = new PaymentMethod();
        pm.id = id;
        pm.userId = userId;
        pm.type = type;
        pm.cardNumber = cardNumber;
        pm.cardCompany = cardCompany;
        pm.bankName = bankName;
        pm.accountNumber = accountNumber;
        pm.isDefault = isDefault;
        pm.createdAt = createdAt;
        pm.updatedAt = updatedAt;
        return pm;
    }

    public void clearDefault() {
        this.isDefault = false;
        this.updatedAt = Instant.now();
    }

    // Getters
    public Long getId() { return id; }
    public Long getUserId() { return userId; }
    public PaymentMethodType getType() { return type; }
    public String getCardNumber() { return cardNumber; }
    public String getCardCompany() { return cardCompany; }
    public String getBankName() { return bankName; }
    public String getAccountNumber() { return accountNumber; }
    public boolean isDefault() { return isDefault; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof PaymentMethod other)) return false;
        return Objects.equals(id, other.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
