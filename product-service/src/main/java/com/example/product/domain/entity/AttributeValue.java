package com.example.product.domain.entity;

import lombok.Getter;

/** 속성 값 (색상→화이트). (순수 POJO). */
@Getter
public class AttributeValue {
    private final Long id;
    private final Long attributeId;
    private final String value;

    private AttributeValue(Long id, Long attributeId, String value) {
        this.id = id;
        this.attributeId = attributeId;
        this.value = value;
    }

    public static AttributeValue create(Long attributeId, String value) {
        return new AttributeValue(null, attributeId, value);
    }

    public static AttributeValue reconstruct(Long id, Long attributeId, String value) {
        return new AttributeValue(id, attributeId, value);
    }
}
