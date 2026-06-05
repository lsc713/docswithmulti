package com.example.user.application.service;

import com.example.user.application.exception.PaymentMethodNotFoundException;
import com.example.user.application.interfaces.PaymentMethodRepository;
import com.example.user.application.usecase.PaymentMethodUseCase;
import com.example.user.domain.entity.PaymentMethod;
import com.example.user.domain.entity.PaymentMethodType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;

@Service
@RequiredArgsConstructor
public class PaymentMethodService implements PaymentMethodUseCase {
    private final PaymentMethodRepository paymentMethodRepository;

    @Override
    @Transactional(readOnly = true)
    public List<PaymentMethod> getPaymentMethods(long userId) { return paymentMethodRepository.findAllByUserId(userId); }

    @Override
    @Transactional
    public PaymentMethod create(long userId, CreateCommand command) {
        PaymentMethod pm;
        if (command.type() == PaymentMethodType.CARD) {
            pm = PaymentMethod.ofCard(userId, command.cardNumber(), command.cardCompany(), command.isDefault());
        } else {
            pm = PaymentMethod.ofBankTransfer(userId, command.bankName(), command.accountNumber(), command.isDefault());
        }
        return paymentMethodRepository.save(pm);
    }

    @Override
    @Transactional
    public void delete(long userId, long paymentMethodId) {
        paymentMethodRepository.findByIdAndUserId(paymentMethodId, userId)
                .orElseThrow(() -> new PaymentMethodNotFoundException(paymentMethodId));
        paymentMethodRepository.deleteById(paymentMethodId);
    }
}
