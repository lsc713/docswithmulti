package com.example.product.infrastructure.persistence;

import com.example.product.domain.entity.Attribute;
import jakarta.persistence.*;

@Entity
@Table(name = "attribute")
public class AttributeJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String name;

    protected AttributeJpaEntity() {}

    public static AttributeJpaEntity from(Attribute a) {
        AttributeJpaEntity e = new AttributeJpaEntity();
        e.id = a.getId();
        e.name = a.getName();
        return e;
    }

    public Attribute toDomain() {
        return Attribute.reconstruct(id, name);
    }
}
