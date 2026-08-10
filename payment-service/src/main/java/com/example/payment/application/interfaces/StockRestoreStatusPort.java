package com.example.payment.application.interfaces;

import com.example.payment.application.model.CancelRestoreLegSnapshot;

import java.util.List;

public interface StockRestoreStatusPort {

    CancelRestoreLegSnapshot inspect(Command command);

    record Command(String cancelRequestId, String paymentKey, List<Item> items) {
        public Command {
            items = List.copyOf(items);
        }
    }

    record Item(long skuId, int quantity) {}
}
