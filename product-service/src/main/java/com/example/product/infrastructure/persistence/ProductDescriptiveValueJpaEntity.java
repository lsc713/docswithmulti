package com.example.product.infrastructure.persistence;

import jakarta.persistence.*;

import java.io.Serializable;
import java.util.Objects;

/** 복합키 (product_id, attribute_value_id) → @IdClass. 상품 레벨 서술값(다속성·다값). */
@Entity
@Table(name = "product_descriptive_value")
@IdClass(ProductDescriptiveValueJpaEntity.Pk.class)
public class ProductDescriptiveValueJpaEntity {

    @Id
    @Column(name = "product_id")
    private Long productId;

    @Id
    @Column(name = "attribute_value_id")
    private Long attributeValueId;

    protected ProductDescriptiveValueJpaEntity() {}

    public static ProductDescriptiveValueJpaEntity of(Long productId, Long attributeValueId) {
        ProductDescriptiveValueJpaEntity e = new ProductDescriptiveValueJpaEntity();
        e.productId = productId;
        e.attributeValueId = attributeValueId;
        return e;
    }

    public static class Pk implements Serializable {
        private Long productId;
        private Long attributeValueId;

        public Pk() {}

        public Pk(Long productId, Long attributeValueId) {
            this.productId = productId;
            this.attributeValueId = attributeValueId;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Pk pk)) return false;
            return Objects.equals(productId, pk.productId) && Objects.equals(attributeValueId, pk.attributeValueId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(productId, attributeValueId);
        }
    }
}
