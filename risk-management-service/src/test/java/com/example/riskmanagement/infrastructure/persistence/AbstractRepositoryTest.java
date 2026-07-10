package com.example.riskmanagement.infrastructure.persistence;

import com.example.riskmanagement.infrastructure.config.PersistenceConfig;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;

/**
 * 통합 테스트 공통 베이스.
 *
 * <p>MySQL 컨테이너는 <b>JVM당 1개 싱글톤</b>으로 띄운다(정적 블록에서 1회 start).
 * {@code @Testcontainers}/{@code @Container}(클래스별 start/stop)를 쓰면, 캐시된 Spring 컨텍스트가
 * 여러 IT 클래스에 공유되는데 앞 클래스가 컨테이너를 stop 하면 뒤 클래스가 죽은 컨테이너를 가리켜
 * "Could not open EntityManager" 로 깨진다. 싱글톤은 컨테이너를 stop 하지 않고 Ryuk 이 JVM 종료 시 정리.
 */
@SpringBootTest(classes = {PersistenceConfig.class})
@EnableAutoConfiguration
public abstract class AbstractRepositoryTest {

    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
        .withDatabaseName("risk_management_test")
        .withUsername("test")
        .withPassword("test");

    static {
        MYSQL.start(); // 최초 로드 시 1회. 명시적 stop 없음(Ryuk 이 JVM 종료 시 정리).
    }

    @DynamicPropertySource
    public static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
        registry.add("spring.flyway.enabled", () -> "false");
    }
}
