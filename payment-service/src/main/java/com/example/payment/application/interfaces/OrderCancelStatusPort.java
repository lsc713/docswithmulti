package com.example.payment.application.interfaces;

import com.example.payment.application.model.CancelRestoreLegSnapshot;

import java.util.List;

public interface OrderCancelStatusPort {

    CancelRestoreLegSnapshot inspect(Command command);

    record Command(String cancelRequestId, List<Long> orderItemIds) {
        public Command {
            orderItemIds = List.copyOf(orderItemIds);
        }
    }
}
