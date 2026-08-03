package com.example.product.application.service;

import com.example.product.application.interfaces.AttributeRepository;
import com.example.product.common.exception.application.AttributeNotFoundException;
import com.example.product.domain.entity.Attribute;
import com.example.product.domain.entity.AttributeValue;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** 전역 속성 사전 (spec §5). 이름/값 유일은 어댑터 UK 번역, 없는 속성은 404. */
@Service
public class AttributeService {

    private final AttributeRepository repository;

    public AttributeService(AttributeRepository repository) {
        this.repository = repository;
    }

    public record DictionaryValue(Long id, String value) {}

    public record DictionaryAttribute(Long id, String name, List<DictionaryValue> values) {}

    @Transactional
    public Long createAttribute(String name) {
        return repository.saveAttribute(Attribute.create(name)).getId();
    }

    @Transactional
    public Long addValue(Long attributeId, String value) {
        if (!repository.existsAttribute(attributeId)) {
            throw new AttributeNotFoundException(attributeId);
        }
        return repository.saveValue(AttributeValue.create(attributeId, value)).getId();
    }

    /** 전체 사전(페이징 없음, 설계 결정). 속성 id asc, 값 id asc. 값 없는 속성도 빈 배열로 포함. */
    @Transactional(readOnly = true)
    public List<DictionaryAttribute> getDictionary() {
        Map<Long, List<DictionaryValue>> valuesByAttr = repository.findAllValues().stream()
                .sorted(Comparator.comparing(AttributeValue::getId))
                .collect(Collectors.groupingBy(AttributeValue::getAttributeId,
                        Collectors.mapping(v -> new DictionaryValue(v.getId(), v.getValue()), Collectors.toList())));
        return repository.findAllAttributes().stream()
                .sorted(Comparator.comparing(Attribute::getId))
                .map(a -> new DictionaryAttribute(a.getId(), a.getName(),
                        valuesByAttr.getOrDefault(a.getId(), List.of())))
                .toList();
    }
}
