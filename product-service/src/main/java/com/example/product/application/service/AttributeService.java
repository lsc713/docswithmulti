package com.example.product.application.service;

import com.example.product.application.interfaces.AttributeRepository;
import com.example.product.domain.entity.Attribute;
import com.example.product.domain.entity.AttributeValue;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 전역 속성 사전 (spec §5). 이름/값 유일은 어댑터 UK 번역 (Task 2). */
@Service
public class AttributeService {

    private final AttributeRepository repository;

    public AttributeService(AttributeRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public Long createAttribute(String name) {
        return repository.saveAttribute(Attribute.create(name)).getId();
    }

    @Transactional
    public Long addValue(Long attributeId, String value) {
        return repository.saveValue(AttributeValue.create(attributeId, value)).getId();
    }
}
