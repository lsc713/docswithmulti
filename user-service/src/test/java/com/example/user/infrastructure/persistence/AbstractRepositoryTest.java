package com.example.user.infrastructure.persistence;

import com.example.user.infrastructure.config.PersistenceConfig;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.MySQLContainer;

@SpringBootTest(classes = {PersistenceConfig.class})
@EnableAutoConfiguration
@Import(PersistenceConfig.class)
@Transactional
public abstract class AbstractRepositoryTest {
    static final MySQLContainer<?> mysql;
    static {
        mysql = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("user_test")
            .withUsername("test")
            .withPassword("test");
        mysql.start();
    }
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
        registry.add("jwt.secret", () -> "test-secret-key-must-be-at-least-256-bits-long-for-hmac-sha256-algo");
        registry.add("jwt.access-token-expiry", () -> "3600000");
        registry.add("jwt.refresh-token-expiry", () -> "604800000");
    }
}
