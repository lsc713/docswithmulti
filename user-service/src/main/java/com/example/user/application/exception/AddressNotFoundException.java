package com.example.user.application.exception;

import com.example.user.common.exception.BusinessException;
import com.example.user.common.exception.ErrorCode;

public class AddressNotFoundException extends BusinessException {
    public AddressNotFoundException(long addressId) {
        super(ErrorCode.ADDRESS_NOT_FOUND, "배송지를 찾을 수 없습니다. id=" + addressId);
    }
}
