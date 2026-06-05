package com.example.user.domain.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Address 도메인 엔티티")
class AddressTest {
    @Test
    @DisplayName("배송지 생성")
    void shouldCreateAddress() {
        Address address = Address.of(1L, "집", "홍길동", "010-1234-5678", "06123", "서울시 강남구", "101호", false);
        assertEquals(1L, address.getUserId());
        assertEquals("집", address.getLabel());
        assertEquals("홍길동", address.getRecipient());
        assertFalse(address.isDefault());
    }

    @Test
    @DisplayName("배송지 정보 수정")
    void shouldUpdateAddress() {
        Address address = Address.of(1L, "집", "홍길동", "010-1234-5678", "06123", "서울시 강남구", "101호", false);
        address.update("회사", "김철수", "010-9999-0000", "03123", "서울시 종로구", "5층", true);
        assertEquals("회사", address.getLabel());
        assertEquals("김철수", address.getRecipient());
        assertTrue(address.isDefault());
    }

    @Test
    @DisplayName("기본 배송지 해제")
    void shouldClearDefault() {
        Address address = Address.of(1L, "집", "홍길동", "010-1234-5678", "06123", "서울시 강남구", "101호", true);
        address.clearDefault();
        assertFalse(address.isDefault());
    }
}
