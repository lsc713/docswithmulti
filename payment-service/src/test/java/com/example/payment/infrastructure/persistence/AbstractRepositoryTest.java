package com.example.payment.infrastructure.persistence;

import com.example.payment.infrastructure.config.PersistenceConfig;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.MySQLContainer;

/**
 * Testcontainers를 사용한 Repository 통합 테스트 베이스 클래스.
 *
 * 싱글턴 컨테이너 패턴 사용:
 *   - static 블록으로 컨테이너를 한 번만 기동
 *   - JVM 종료 시 자동 정지 (Ryuk 컨테이너가 처리)
 *   - 여러 서브클래스가 같은 MySQL 인스턴스를 공유
 *
 * @Transactional: 각 테스트 메서드 실행 후 자동 롤백 → 테스트 간 데이터 격리
 */
@SpringBootTest(classes = {PersistenceConfig.class})
@EnableAutoConfiguration
@Import(PersistenceConfig.class)
@Transactional
public abstract class AbstractRepositoryTest {

    static final MySQLContainer<?> mysql;

    static {
        mysql = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("payment_test")
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
}
