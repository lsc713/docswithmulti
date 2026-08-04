package com.example.order.domain.exception;

import com.example.order.common.exception.BusinessException;
import com.example.order.common.exception.ErrorCode;

/** 장바구니에 해당 skuId 항목이 없을 때(updateQuantity 등). */
public class CartItemNotFoundException extends BusinessException {
    public CartItemNotFoundException() {
        super(ErrorCode.CART_ITEM_NOT_FOUND);
    }
}
