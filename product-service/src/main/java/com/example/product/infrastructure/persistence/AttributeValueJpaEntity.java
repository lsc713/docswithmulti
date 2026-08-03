package com.example.product.infrastructure.persistence;

import com.example.product.domain.entity.AttributeValue;
import jakarta.persistence.*;

@Entity
@Table(name = "attribute_value")
public class AttributeValueJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "attribute_id", nullable = false)
    private Long attributeId;

    @Column(name = "value", nullable = false, length = 100)
    private String value;

    protected AttributeValueJpaEntity() {}

    public static AttributeValueJpaEntity from(AttributeValue v) {
        AttributeValueJpaEntity e = new AttributeValueJpaEntity();
        e.id = v.getId();
        e.attributeId = v.getAttributeId();
        e.value = v.getValue();
        return e;
    }

    public AttributeValue toDomain() {
        return AttributeValue.reconstruct(id, attributeId, value);
    }
}
