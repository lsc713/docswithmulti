package com.example.product.application.service;

import java.util.Set;

public record ProductStockChangedEvent(Set<Long> productIds) {
}
