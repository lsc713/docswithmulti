package com.example.product.infrastructure.persistence;

import com.example.product.application.interfaces.AttributeRepository;
import com.example.product.domain.entity.Attribute;
import com.example.product.domain.entity.AttributeValue;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

public class AttributeRepositoryImpl implements AttributeRepository {

    private final AttributeJpaRepository attributeJpa;
    private final AttributeValueJpaRepository valueJpa;

    public AttributeRepositoryImpl(AttributeJpaRepository attributeJpa, AttributeValueJpaRepository valueJpa) {
        this.attributeJpa = attributeJpa;
        this.valueJpa = valueJpa;
    }

    @Override
    public Attribute saveAttribute(Attribute attribute) {
        // Task 2: uk_attribute_name 위반 → AttributeNameDuplicateException 번역 (saveAndFlush).
        return attributeJpa.saveAndFlush(AttributeJpaEntity.from(attribute)).toDomain();
    }

    @Override
    public AttributeValue saveValue(AttributeValue value) {
        // Task 2: uk_attribute_value 위반 → AttributeValueDuplicateException 번역.
        return valueJpa.saveAndFlush(AttributeValueJpaEntity.from(value)).toDomain();
    }

    @Override
    public boolean existsAttribute(Long id) {
        return id != null && attributeJpa.existsById(id);
    }

    @Override
    public List<Attribute> findAllAttributes() {
        return attributeJpa.findAll().stream().map(AttributeJpaEntity::toDomain).toList();
    }

    @Override
    public List<AttributeValue> findAllValues() {
        return valueJpa.findAll().stream().map(AttributeValueJpaEntity::toDomain).toList();
    }

    @Override
    public Map<Long, Long> findAttributeIdByValueIds(Collection<Long> valueIds) {
        if (valueIds.isEmpty()) return Map.of();
        return valueJpa.findByIdIn(valueIds).stream()
                .map(AttributeValueJpaEntity::toDomain)
                .collect(Collectors.toMap(AttributeValue::getId, AttributeValue::getAttributeId,
                        (a, b) -> a, java.util.LinkedHashMap::new));
    }
}
