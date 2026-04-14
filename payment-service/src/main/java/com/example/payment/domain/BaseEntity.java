package com.example.payment.domain;

import java.time.LocalDateTime;

public class BaseEntity {

    protected BaseEntity() {
    }

    protected LocalDateTime createdAt;
    protected LocalDateTime updatedAt;

}
