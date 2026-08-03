package com.example.product.infrastructure.persistence;

import com.example.product.application.interfaces.AttributeRepository;
import com.example.product.common.exception.application.AttributeNameDuplicateException;
import com.example.product.common.exception.application.AttributeValueDuplicateException;
import com.example.product.domain.entity.Attribute;
import com.example.product.domain.entity.AttributeValue;
import org.springframework.dao.DataIntegrityViolationException;

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
        try {
            // uk_attribute_name 위반을 DB가 원자적으로 잡고(TOCTOU 없음) 도메인 예외로 번역.
            return attributeJpa.saveAndFlush(AttributeJpaEntity.from(attribute)).toDomain();
        } catch (DataIntegrityViolationException e) {
            throw new AttributeNameDuplicateException();
        }
    }

    @Override
    public AttributeValue saveValue(AttributeValue value) {
        try {
            // uk_attribute_value(attribute_id, value) 위반 → 도메인 예외로 번역.
            return valueJpa.saveAndFlush(AttributeValueJpaEntity.from(value)).toDomain();
        } catch (DataIntegrityViolationException e) {
            throw new AttributeValueDuplicateException();
        }
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
