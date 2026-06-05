package com.example.user.application.service;

import com.example.user.common.exception.application.AddressNotFoundException;
import com.example.user.application.interfaces.AddressRepository;
import com.example.user.application.usecase.AddressUseCase.*;
import com.example.user.domain.entity.Address;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("AddressService")
class AddressServiceTest {
    @Mock AddressRepository addressRepository;
    private AddressService addressService;

    @BeforeEach
    void setUp() { addressService = new AddressService(addressRepository); }

    private Address sampleAddress() {
        return Address.reconstruct(1L, 1L, "집", "홍길동", "010-1234-5678",
                "12345", "서울시", "101호", true, Instant.now(), Instant.now());
    }

    @Test @DisplayName("getAddresses — 유저의 배송지 목록 조회")
    void shouldGetAddresses() {
        when(addressRepository.findAllByUserId(1L)).thenReturn(List.of(sampleAddress()));
        assertEquals(1, addressService.getAddresses(1L).size());
    }

    @Test @DisplayName("create — 배송지 생성")
    void shouldCreateAddress() {
        when(addressRepository.save(any())).thenReturn(sampleAddress());
        Address result = addressService.create(1L,
                new CreateCommand("집", "홍길동", "010-1234-5678", "12345", "서울시", "101호", true));
        assertEquals("집", result.getLabel());
    }

    @Test @DisplayName("update — 배송지 수정")
    void shouldUpdateAddress() {
        when(addressRepository.findByIdAndUserId(1L, 1L)).thenReturn(Optional.of(sampleAddress()));
        when(addressRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        Address result = addressService.update(1L, 1L,
                new UpdateCommand("회사", "홍길동", "010-9999-0000", "54321", "부산시", "202호", false));
        assertEquals("회사", result.getLabel());
    }

    @Test @DisplayName("update — 없는 배송지 → AddressNotFoundException")
    void shouldThrowOnUpdateNotFound() {
        when(addressRepository.findByIdAndUserId(99L, 1L)).thenReturn(Optional.empty());
        assertThrows(AddressNotFoundException.class, () ->
                addressService.update(1L, 99L,
                        new UpdateCommand("회사", "홍길동", "010", "54321", "부산시", "202호", false)));
    }

    @Test @DisplayName("delete — 배송지 삭제")
    void shouldDeleteAddress() {
        when(addressRepository.findByIdAndUserId(1L, 1L)).thenReturn(Optional.of(sampleAddress()));
        addressService.delete(1L, 1L);
        verify(addressRepository).deleteById(1L);
    }

    @Test @DisplayName("delete — 없는 배송지 → AddressNotFoundException")
    void shouldThrowOnDeleteNotFound() {
        when(addressRepository.findByIdAndUserId(99L, 1L)).thenReturn(Optional.empty());
        assertThrows(AddressNotFoundException.class, () -> addressService.delete(1L, 99L));
    }
}
