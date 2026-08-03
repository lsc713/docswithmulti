package com.example.product.infrastructure.persistence;

import com.example.product.domain.entity.ProductAttribute;
import jakarta.persistence.*;

import java.io.Serializable;
import java.util.Objects;

/** 복합키 (product_id, attribute_id) → @IdClass. */
@Entity
@Table(name = "product_attribute")
@IdClass(ProductAttributeJpaEntity.Pk.class)
public class ProductAttributeJpaEntity {

    @Id
    @Column(name = "product_id")
    private Long productId;

    @Id
    @Column(name = "attribute_id")
    private Long attributeId;

    @Column(name = "is_variant", nullable = false)
    private boolean isVariant;

    protected ProductAttributeJpaEntity() {}

    public static ProductAttributeJpaEntity from(ProductAttribute pa) {
        ProductAttributeJpaEntity e = new ProductAttributeJpaEntity();
        e.productId = pa.getProductId();
        e.attributeId = pa.getAttributeId();
        e.isVariant = pa.isVariant();
        return e;
    }

    public static class Pk implements Serializable {
        private Long productId;
        private Long attributeId;

        public Pk() {}

        public Pk(Long productId, Long attributeId) {
            this.productId = productId;
            this.attributeId = attributeId;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Pk pk)) return false;
            return Objects.equals(productId, pk.productId) && Objects.equals(attributeId, pk.attributeId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(productId, attributeId);
        }
    }
}
