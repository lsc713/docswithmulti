package com.example.product.integration;

import com.example.product.application.service.ProcessCancelledStockService;
import com.example.product.application.service.StockService;
import com.example.product.application.usecase.ProcessCancelledStockUseCase.Command;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 취소 복원 멱등·부분취소 하드닝 (RST-02, D-P3-2/D-P3-4) — 실 MySQL(Testcontainers).
 *
 * <p>Consumer/Kafka 없이 {@link ProcessCancelledStockService} 서비스 레벨로 직접 검증(03-01 tracer 가
 * Kafka 배선을 이미 증명). processed_cancel_event UK 멱등 게이트로 at-least-once 중복 이벤트가 재고를
 * 과다 복원하지 않고, 부분취소 시 cancelledItems 에 실린 SKU 만 복원됨을 고정한다.
 *
 * <p>useAffectedRows=true — releaseIfReserved 조건부 전이가 affected=1 을 정확히 보고해야 복원 트리거.
 * Docker 필요.
 */
@SpringBootTest
@Testcontainers
@DisplayName("Cancel restore 멱등·부분취소 (중복 no-op · cancelledItems SKU만 복원)")
class CancelRestoreIdempotencyIntegrationTest {

    @Container
    static final MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("product_db")
            .withUsername("product")
            .withPassword("product")
            .withUrlParam("useAffectedRows", "true");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", mysql::getJdbcUrl);
        r.add("spring.datasource.username", mysql::getUsername);
        r.add("spring.datasource.password", mysql::getPassword);
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired StockService stockService;
    @Autowired ProcessCancelledStockService processService;

    @Test
    @DisplayName("RST-02: 같은 cancelRequestId 2회 실행 → 재고 1회분만 복원, processed_cancel_event 1행")
    void duplicateEventIsIdempotentNoOp() {
        long skuId = seedSku("IDEM-1", 5);
        String paymentKey = "PAY-IDEM-1";
        String cancelRequestId = "9101";

        // reserve qty=3 → available 5→2
        stockService.reserve(paymentKey, List.of(new StockService.ReserveItem(skuId, 3)));
        assertThat(availableQty(skuId)).isEqualTo(2);

        Command cmd = new Command(cancelRequestId, paymentKey, List.of(new Command.Item(skuId, 3)));

        // 1회차: 복원 → available 5, processed_cancel_event 1행
        processService.execute(cmd);
        assertThat(availableQty(skuId)).isEqualTo(5);
        assertThat(processedEventCount(cancelRequestId)).isEqualTo(1);

        // 2회차(중복): no-op → 추가 복원 없음, processed_cancel_event 여전히 1행
        processService.execute(cmd);
        assertThat(availableQty(skuId)).isEqualTo(5); // 8 아님 — 과다 복원 없음
        assertThat(processedEventCount(cancelRequestId)).isEqualTo(1);
    }

    @Test
    @DisplayName("RST-02: 부분취소 → cancelledItems SKU만 복원, 나머지 예약은 RESERVED 유지")
    void partialCancelRestoresOnlyCancelledSku() {
        long skuA = seedSku("PART-A", 5);
        long skuB = seedSku("PART-B", 8);
        String paymentKey = "PAY-PART-1";

        // 두 SKU 예약: A qty=3 (5→2), B qty=4 (8→4)
        stockService.reserve(paymentKey, List.of(
                new StockService.ReserveItem(skuA, 3),
                new StockService.ReserveItem(skuB, 4)));
        assertThat(availableQty(skuA)).isEqualTo(2);
        assertThat(availableQty(skuB)).isEqualTo(4);

        // 부분취소: A 만 cancelledItems 에 담아 실행
        processService.execute(new Command("9201", paymentKey, List.of(new Command.Item(skuA, 3))));

        // A 만 복원, B 는 그대로 + B 예약 RESERVED 유지
        assertThat(availableQty(skuA)).isEqualTo(5);
        assertThat(availableQty(skuB)).isEqualTo(4);
        assertThat(reservationStatus(paymentKey, skuA)).isEqualTo("RELEASED");
        assertThat(reservationStatus(paymentKey, skuB)).isEqualTo("RESERVED");
    }

    // --- helpers ---

    private long seedSku(String code, int stock) {
        jdbc.update("INSERT INTO product(name) VALUES ('티셔츠')");
        Long productId = jdbc.queryForObject("SELECT id FROM product ORDER BY id DESC LIMIT 1", Long.class);
        jdbc.update("INSERT INTO product_sku(product_id, sku_code, option_summary) VALUES (?, ?, 'opt')",
                productId, code);
        long skuId = jdbc.queryForObject("SELECT id FROM product_sku WHERE sku_code = ?", Long.class, code);
        jdbc.update("INSERT INTO product_stock(sku_id, available_qty) VALUES (?, ?)", skuId, stock);
        return skuId;
    }

    private int availableQty(long skuId) {
        return jdbc.queryForObject(
                "SELECT available_qty FROM product_stock WHERE sku_id = ?", Integer.class, skuId);
    }

    private String reservationStatus(String paymentKey, long skuId) {
        return jdbc.queryForObject(
                "SELECT status FROM stock_reservation WHERE payment_key = ? AND sku_id = ?",
                String.class, paymentKey, skuId);
    }

    private int processedEventCount(String cancelRequestId) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM processed_cancel_event WHERE cancel_request_id = ?",
                Integer.class, cancelRequestId);
    }
}
