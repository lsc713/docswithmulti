package com.example.product.infrastructure.persistence;

import jakarta.persistence.*;

import java.io.Serializable;
import java.util.Objects;

/** 복합키 (sku_id, attribute_value_id) → @IdClass. */
@Entity
@Table(name = "sku_attribute_value")
@IdClass(SkuAttributeValueJpaEntity.Pk.class)
public class SkuAttributeValueJpaEntity {

    @Id
    @Column(name = "sku_id")
    private Long skuId;

    @Id
    @Column(name = "attribute_value_id")
    private Long attributeValueId;

    protected SkuAttributeValueJpaEntity() {}

    public static SkuAttributeValueJpaEntity of(Long skuId, Long attributeValueId) {
        SkuAttributeValueJpaEntity e = new SkuAttributeValueJpaEntity();
        e.skuId = skuId;
        e.attributeValueId = attributeValueId;
        return e;
    }

    public static class Pk implements Serializable {
        private Long skuId;
        private Long attributeValueId;

        public Pk() {}

        public Pk(Long skuId, Long attributeValueId) {
            this.skuId = skuId;
            this.attributeValueId = attributeValueId;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Pk pk)) return false;
            return Objects.equals(skuId, pk.skuId) && Objects.equals(attributeValueId, pk.attributeValueId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(skuId, attributeValueId);
        }
    }
}
