package com.example.product.presentation.dto;

import com.example.product.application.service.AttributeService.DictionaryAttribute;

import java.util.List;

/** GET /v1/attributes 응답: 전역 속성·값 목록(페이징 없음). */
public record AttributeDictionaryResponse(List<Attr> attributes) {

    public record Attr(Long id, String name, List<Val> values) {}

    public record Val(Long id, String value) {}

    public static AttributeDictionaryResponse from(List<DictionaryAttribute> dict) {
        List<Attr> attrs = dict.stream()
                .map(a -> new Attr(a.id(), a.name(), a.values().stream()
                        .map(v -> new Val(v.id(), v.value()))
                        .toList()))
                .toList();
        return new AttributeDictionaryResponse(attrs);
    }
}
