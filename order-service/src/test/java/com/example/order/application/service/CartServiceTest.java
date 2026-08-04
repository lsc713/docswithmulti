package com.example.order.application.service;

import com.example.order.application.interfaces.CartRepository;
import com.example.order.application.usecase.CartUseCase;
import com.example.order.domain.entity.CartItem;
import com.example.order.domain.exception.CartItemNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class CartServiceTest {

    private CartRepository cartRepository;
    private CartService service;

    @BeforeEach
    void setUp() {
        cartRepository = mock(CartRepository.class);
        service = new CartService(cartRepository);
    }

    @Test
    void addItem_creates_new_when_absent() {
        when(cartRepository.findByUserIdAndSkuId(7L, 42L)).thenReturn(Optional.empty());
        when(cartRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        CartItem result = service.addItem(7L,
            new CartUseCase.AddCommand(42L, 1L, "티", "블랙/M", 29000L, 3));

        assertThat(result.getQuantity()).isEqualTo(3);
        verify(cartRepository).save(any());
    }

    @Test
    void addItem_merges_quantity_when_exists() {
        when(cartRepository.findByUserIdAndSkuId(7L, 42L))
            .thenReturn(Optional.of(CartItem.of(1L, 7L, 42L, 1L, "티", "블랙/M", 29000L, 2)));
        when(cartRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        CartItem result = service.addItem(7L,
            new CartUseCase.AddCommand(42L, 1L, "티", "블랙/M", 29000L, 3));

        assertThat(result.getQuantity()).isEqualTo(5);
    }

    @Test
    void updateQuantity_sets_when_exists() {
        when(cartRepository.findByUserIdAndSkuId(7L, 42L))
            .thenReturn(Optional.of(CartItem.of(1L, 7L, 42L, 1L, "티", "블랙/M", 29000L, 2)));
        when(cartRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        CartItem result = service.updateQuantity(7L, 42L, 9);

        assertThat(result.getQuantity()).isEqualTo(9);
    }

    @Test
    void updateQuantity_absent_throws() {
        when(cartRepository.findByUserIdAndSkuId(7L, 99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.updateQuantity(7L, 99L, 4))
            .isInstanceOf(CartItemNotFoundException.class);
    }

    @Test
    void removeItem_delegates_to_repository() {
        service.removeItem(7L, 42L);

        verify(cartRepository).deleteByUserIdAndSkuId(7L, 42L);
    }

    @Test
    void clear_delegates_to_repository() {
        service.clear(7L);

        verify(cartRepository).deleteByUserId(7L);
    }

    @Test
    void getCart_delegates_to_repository() {
        List<CartItem> items = List.of(CartItem.of(1L, 7L, 42L, 1L, "티", "블랙/M", 29000L, 2));
        when(cartRepository.findByUserId(7L)).thenReturn(items);

        List<CartItem> result = service.getCart(7L);

        assertThat(result).isEqualTo(items);
    }
}
