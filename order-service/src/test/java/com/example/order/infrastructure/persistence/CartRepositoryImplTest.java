package com.example.order.infrastructure.persistence;

import com.example.order.application.interfaces.CartRepository;
import com.example.order.domain.entity.CartItem;
import com.example.order.infrastructure.config.PersistenceConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.MySQLContainer;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * cart_item(V5) 영속 계층 통합 테스트.
 * payment-service AbstractRepositoryTest 패턴 미러(싱글턴 Testcontainers MySQL + PersistenceConfig 단독 로드).
 * order-service 전용 base class 는 없어 이 클래스 하나에 인라인(테스트 1개뿐이라 별도 base 불필요 — ponytail).
 */
@SpringBootTest(classes = {PersistenceConfig.class})
@EnableAutoConfiguration
@Import(PersistenceConfig.class)
@Transactional
class CartRepositoryImplTest {

    static final MySQLContainer<?> mysql;

    static {
        mysql = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("order_test")
            .withUsername("test")
            .withPassword("test");
        mysql.start();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
    }

    @Autowired
    private CartRepository cartRepository;

    @Autowired
    private JdbcTemplate jdbc;

    private CartItem newItem(long userId, long skuId) {
        return CartItem.create(userId, skuId, 900L, "티셔츠", "블랙/M", 19000L, 1);
    }

    @Test
    void save_new_then_findByUserId_returns_it_with_matching_fields() {
        CartItem saved = cartRepository.save(newItem(1L, 100L));

        List<CartItem> found = cartRepository.findByUserId(1L);

        assertThat(found).hasSize(1);
        CartItem item = found.get(0);
        assertThat(item.getId()).isEqualTo(saved.getId());
        assertThat(item.getUserId()).isEqualTo(1L);
        assertThat(item.getSkuId()).isEqualTo(100L);
        assertThat(item.getProductId()).isEqualTo(900L);
        assertThat(item.getItemName()).isEqualTo("티셔츠");
        assertThat(item.getOptionSummary()).isEqualTo("블랙/M");
        assertThat(item.getUnitPrice()).isEqualTo(19000L);
        assertThat(item.getQuantity()).isEqualTo(1);
    }

    @Test
    void save_with_id_merges_quantity_keeps_single_row_and_preserves_created_at() {
        CartItem saved = cartRepository.save(newItem(2L, 200L));
        Timestamp createdAtBefore = jdbc.queryForObject(
            "SELECT created_at FROM cart_item WHERE id = ?", Timestamp.class, saved.getId());

        CartItem toUpdate = CartItem.of(saved.getId(), saved.getUserId(), saved.getSkuId(),
            saved.getProductId(), saved.getItemName(), saved.getOptionSummary(),
            saved.getUnitPrice(), 5);
        cartRepository.save(toUpdate);

        List<CartItem> found = cartRepository.findByUserId(2L);
        assertThat(found).hasSize(1);
        assertThat(found.get(0).getQuantity()).isEqualTo(5);

        Timestamp createdAtAfter = jdbc.queryForObject(
            "SELECT created_at FROM cart_item WHERE id = ?", Timestamp.class, saved.getId());
        assertThat(createdAtAfter).isEqualTo(createdAtBefore);
    }

    @Test
    void findByUserIdAndSkuId_present_and_absent() {
        cartRepository.save(newItem(3L, 300L));

        Optional<CartItem> present = cartRepository.findByUserIdAndSkuId(3L, 300L);
        Optional<CartItem> absent = cartRepository.findByUserIdAndSkuId(3L, 999L);

        assertThat(present).isPresent();
        assertThat(present.get().getSkuId()).isEqualTo(300L);
        assertThat(absent).isEmpty();
    }

    @Test
    void deleteByUserIdAndSkuId_removes_only_that_row() {
        cartRepository.save(newItem(4L, 400L));
        cartRepository.save(newItem(4L, 401L));

        cartRepository.deleteByUserIdAndSkuId(4L, 400L);

        assertThat(cartRepository.findByUserIdAndSkuId(4L, 400L)).isEmpty();
        assertThat(cartRepository.findByUserId(4L)).hasSize(1);
    }

    @Test
    void deleteByUserId_removes_all_rows_for_user() {
        cartRepository.save(newItem(5L, 500L));
        cartRepository.save(newItem(5L, 501L));

        cartRepository.deleteByUserId(5L);

        assertThat(cartRepository.findByUserId(5L)).isEmpty();
    }

    @Test
    void user_isolation_userA_rows_absent_from_userB_findByUserId() {
        cartRepository.save(newItem(6L, 600L));
        cartRepository.save(newItem(7L, 700L));

        List<CartItem> userBItems = cartRepository.findByUserId(7L);

        assertThat(userBItems).hasSize(1);
        assertThat(userBItems.get(0).getUserId()).isEqualTo(7L);
    }
}
