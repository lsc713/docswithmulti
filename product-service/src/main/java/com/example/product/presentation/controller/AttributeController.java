package com.example.product.presentation.controller;

import com.example.product.application.service.AttributeService;
import com.example.product.presentation.dto.AttributeDictionaryResponse;
import com.example.product.presentation.dto.AttributeIdResponse;
import com.example.product.presentation.dto.CreateAttributeRequest;
import com.example.product.presentation.dto.CreateAttributeValueRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 전역 속성 사전 (spec §5): 속성/값 생성 + 사전 조회. */
@RestController
@RequestMapping("/v1/attributes")
public class AttributeController {

    private final AttributeService attributeService;

    public AttributeController(AttributeService attributeService) {
        this.attributeService = attributeService;
    }

    @PostMapping
    public AttributeIdResponse create(@Valid @RequestBody CreateAttributeRequest req) {
        return new AttributeIdResponse(attributeService.createAttribute(req.name()));
    }

    @PostMapping("/{id}/values")
    public AttributeIdResponse addValue(@PathVariable Long id,
                                        @Valid @RequestBody CreateAttributeValueRequest req) {
        return new AttributeIdResponse(attributeService.addValue(id, req.value()));
    }

    @GetMapping
    public AttributeDictionaryResponse dictionary() {
        return AttributeDictionaryResponse.from(attributeService.getDictionary());
    }
}
