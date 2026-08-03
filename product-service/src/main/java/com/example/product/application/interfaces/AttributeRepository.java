package com.example.product.application.interfaces;

import com.example.product.domain.entity.Attribute;
import com.example.product.domain.entity.AttributeValue;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/** 전역 속성 사전 포트. UK 위반은 어댑터가 도메인 예외로 번역(DB-불가지론). */
public interface AttributeRepository {

    /** name UK(uk_attribute_name) 위반 → AttributeNameDuplicateException. */
    Attribute saveAttribute(Attribute attribute);

    /** (attribute_id, value) UK(uk_attribute_value) 위반 → AttributeValueDuplicateException. */
    AttributeValue saveValue(AttributeValue value);

    boolean existsAttribute(Long id);

    List<Attribute> findAllAttributes();

    List<AttributeValue> findAllValues();

    /** 변형 소속 검증용: 존재하는 valueId → attributeId 매핑만 반환. */
    Map<Long, Long> findAttributeIdByValueIds(Collection<Long> valueIds);
}
