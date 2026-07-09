package com.example.merchantlimit.infrastructure.persistence;

import com.example.merchantlimit.infrastructure.config.PersistenceConfig;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.springframework.transaction.annotation.Transactional;

@Testcontainers
@SpringBootTest(classes = {PersistenceConfig.class})
@EnableAutoConfiguration
@Transactional  // 메서드별 롤백 격리 (create-drop은 클래스 종료 시에만 drop → 메서드 간 데이터 오염 방지)
public abstract class AbstractRepositoryTest {

    @Container
    public static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0")
        .withDatabaseName("merchant_limit_test")
        .withUsername("test")
        .withPassword("test");

    @DynamicPropertySource
    public static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
        registry.add("spring.flyway.enabled", () -> "false");
    }
}
