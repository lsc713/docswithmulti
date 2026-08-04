package com.example.order.infrastructure.persistence;

import com.example.order.domain.entity.CartItem;
import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "cart_item")
public class CartItemJpaEntity {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "user_id", nullable = false) private Long userId;
    @Column(name = "sku_id", nullable = false) private Long skuId;
    @Column(name = "product_id", nullable = false) private Long productId;
    @Column(name = "item_name", nullable = false, length = 255) private String itemName;
    @Column(name = "option_summary", length = 255) private String optionSummary;
    @Column(name = "unit_price", nullable = false) private Long unitPrice;
    @Column(nullable = false) private Integer quantity;
    @Column(nullable = false, updatable = false) private Instant createdAt;
    @Column(nullable = false) private Instant updatedAt;

    protected CartItemJpaEntity() {}

    static CartItemJpaEntity forInsert(CartItem c) {
        CartItemJpaEntity e = new CartItemJpaEntity();
        e.userId = c.getUserId(); e.skuId = c.getSkuId(); e.productId = c.getProductId();
        e.itemName = c.getItemName(); e.optionSummary = c.getOptionSummary();
        e.unitPrice = c.getUnitPrice(); e.quantity = c.getQuantity();
        e.createdAt = Instant.now(); e.updatedAt = Instant.now();
        return e;
    }

    void applyQuantity(int quantity) { this.quantity = quantity; this.updatedAt = Instant.now(); }

    CartItem toDomain() {
        return CartItem.of(id, userId, skuId, productId, itemName, optionSummary, unitPrice, quantity);
    }

    Long getId() { return id; }
}
