# product-service 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 상품 카탈로그(버저닝 포함), SKU(color/size), 재고 관리(차감/복원) 모듈을 구현하고 Kafka 기반 재고 복원 Consumer를 추가한다.

**Architecture:** 기존 모듈과 동일한 레이어드 아키텍처(presentation → application → domain ← infrastructure). 도메인 엔티티와 JPA 엔티티 분리, repository 인터페이스는 application에, 구현체는 infrastructure에 위치. JWT 인가는 user-service와 동일한 HMAC-SHA256 공유 시크릿 방식.

**Tech Stack:** Java 21, Spring Boot 4.0.5, Spring Security, Spring Data JPA, Spring Kafka, Flyway, MySQL 8.0, jjwt 0.12.6, JUnit 5 + Mockito + Testcontainers

**Spec:** `docs/superpowers/specs/2026-06-05-product-service-design.md`

---

## 파일 구조

```
product-service/
├── build.gradle
├── Dockerfile
├── src/main/java/com/example/product/
│   ├── ProductServiceApplication.java
│   ├── common/exception/
│   │   ├── BusinessException.java
│   │   ├── ErrorCode.java
│   │   ├── domain/
│   │   │   └── InsufficientStockException.java
│   │   └── application/
│   │       ├── ProductNotFoundException.java
│   │       ├── SkuNotFoundException.java
│   │       ├── CategoryNotFoundException.java
│   │       ├── DuplicateSkuCodeException.java
│   │       └── CategoryHasChildrenException.java
│   ├── domain/entity/
│   │   ├── Category.java
│   │   ├── Product.java
│   │   ├── ProductStatus.java
│   │   ├── ProductVersion.java
│   │   ├── ProductSku.java
│   │   ├── ProductStock.java
│   │   └── ProcessedStockEvent.java
│   ├── application/
│   │   ├── usecase/
│   │   │   ├── CategoryUseCase.java
│   │   │   ├── CreateProductUseCase.java
│   │   │   ├── GetProductUseCase.java
│   │   │   ├── ProductVersionUseCase.java
│   │   │   ├── SkuUseCase.java
│   │   │   ├── StockDeductUseCase.java
│   │   │   └── StockRestoreUseCase.java
│   │   ├── service/
│   │   │   ├── CategoryService.java
│   │   │   ├── CreateProductService.java
│   │   │   ├── GetProductService.java
│   │   │   ├── ProductVersionService.java
│   │   │   ├── SkuService.java
│   │   │   ├── StockDeductService.java
│   │   │   └── StockRestoreService.java
│   │   └── interfaces/
│   │       ├── CategoryRepository.java
│   │       ├── ProductRepository.java
│   │       ├── ProductVersionRepository.java
│   │       ├── ProductSkuRepository.java
│   │       ├── ProductStockRepository.java
│   │       └── ProcessedStockEventRepository.java
│   ├── infrastructure/
│   │   ├── persistence/
│   │   │   ├── CategoryJpaEntity.java
│   │   │   ├── CategoryJpaRepository.java
│   │   │   ├── CategoryRepositoryImpl.java
│   │   │   ├── ProductJpaEntity.java
│   │   │   ├── ProductJpaRepository.java
│   │   │   ├── ProductRepositoryImpl.java
│   │   │   ├── ProductVersionJpaEntity.java
│   │   │   ├── ProductVersionJpaRepository.java
│   │   │   ├── ProductVersionRepositoryImpl.java
│   │   │   ├── ProductSkuJpaEntity.java
│   │   │   ├── ProductSkuJpaRepository.java
│   │   │   ├── ProductSkuRepositoryImpl.java
│   │   │   ├── ProductStockJpaEntity.java
│   │   │   ├── ProductStockJpaRepository.java
│   │   │   ├── ProductStockRepositoryImpl.java
│   │   │   ├── ProcessedStockEventJpaEntity.java
│   │   │   ├── ProcessedStockEventJpaRepository.java
│   │   │   └── ProcessedStockEventRepositoryImpl.java
│   │   ├── messaging/
│   │   │   └── PaymentCancelledStockConsumer.java
│   │   ├── security/
│   │   │   ├── JwtAuthenticationFilter.java
│   │   │   └── SecurityConfig.java
│   │   └── config/
│   │       ├── PersistenceConfig.java
│   │       └── KafkaConsumerConfig.java
│   └── presentation/
│       ├── controller/
│       │   ├── CategoryController.java
│       │   ├── ProductController.java
│       │   ├── SkuController.java
│       │   ├── InternalProductController.java
│       │   └── InternalStockController.java
│       ├── dto/
│       │   ├── CreateCategoryRequest.java
│       │   ├── UpdateCategoryRequest.java
│       │   ├── CategoryResponse.java
│       │   ├── CreateProductRequest.java
│       │   ├── ProductDetailResponse.java
│       │   ├── ProductListResponse.java
│       │   ├── UpdateProductStatusRequest.java
│       │   ├── CreateVersionRequest.java
│       │   ├── VersionResponse.java
│       │   ├── CreateSkuRequest.java
│       │   ├── UpdateStockRequest.java
│       │   ├── StockDeductRequest.java
│       │   ├── StockDeductResponse.java
│       │   └── InternalProductResponse.java
│       └── GlobalExceptionHandler.java
├── src/main/resources/
│   ├── application.yml
│   └── db/migration/V1__create_product_core.sql
└── src/test/java/com/example/product/
    ├── domain/entity/
    │   ├── CategoryTest.java
    │   ├── ProductTest.java
    │   ├── ProductVersionTest.java
    │   ├── ProductSkuTest.java
    │   └── ProductStockTest.java
    ├── application/service/
    │   ├── CategoryServiceTest.java
    │   ├── CreateProductServiceTest.java
    │   ├── StockDeductServiceTest.java
    │   └── StockRestoreServiceTest.java
    └── infrastructure/
        ├── persistence/
        │   └── AbstractRepositoryTest.java
        └── messaging/
            └── PaymentCancelledStockConsumerTest.java
```

### 수정 파일

```
settings.gradle                  — include 'product-service' 이미 존재, 변경 없음
docker-compose.yml               — mysql-product은 이미 존재 (port 3310), product-service 앱 서비스 추가
```

---

## Task 1: 모듈 스캐폴딩

**Files:**
- Modify: `product-service/build.gradle`
- Create: `product-service/src/main/java/com/example/product/ProductServiceApplication.java`
- Create: `product-service/src/main/resources/application.yml`
- Create: `product-service/src/main/resources/db/migration/V1__create_product_core.sql`
- Create: `product-service/Dockerfile`

- [ ] **Step 1: build.gradle 작성**

기존 build.gradle을 덮어쓴다. (현재 거의 비어있음)

```gradle
apply plugin: 'org.flywaydb.flyway'
apply plugin: 'jacoco'

flyway {
    url      = 'jdbc:mysql://localhost:3310/product_db?serverTimezone=UTC&useSSL=false&allowPublicKeyRetrieval=true'
    user     = 'product'
    password = 'product'
    locations = ['classpath:db/migration']
}

jacoco {
    toolVersion = '0.8.12'
}

test {
    finalizedBy jacocoTestReport
}

jacocoTestReport {
    dependsOn test
    reports {
        html.required = true
        html.outputLocation = layout.buildDirectory.dir('reports/jacoco/html')
        xml.required = false
    }
}

dependencies {
    // Spring Security
    implementation 'org.springframework.boot:spring-boot-starter-security'

    // JWT (jjwt)
    implementation 'io.jsonwebtoken:jjwt-api:0.12.6'
    runtimeOnly 'io.jsonwebtoken:jjwt-impl:0.12.6'
    runtimeOnly 'io.jsonwebtoken:jjwt-jackson:0.12.6'

    // Kafka
    implementation 'org.springframework.kafka:spring-kafka'

    // Test
    testImplementation 'org.springframework.security:spring-security-test'
}
```

- [ ] **Step 2: ProductServiceApplication 작성**

```java
package com.example.product;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class ProductServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(ProductServiceApplication.class, args);
    }
}
```

- [ ] **Step 3: application.yml 작성**

```yaml
spring:
  application:
    name: product-service

  datasource:
    url: jdbc:mysql://localhost:3310/product_db?serverTimezone=UTC&useSSL=false&allowPublicKeyRetrieval=true
    username: product
    password: product
    driver-class-name: com.mysql.cj.jdbc.Driver
    hikari:
      connection-timeout: 30000
      initialization-fail-timeout: -1

  jpa:
    hibernate:
      ddl-auto: validate
    show-sql: false
    properties:
      hibernate:
        format_sql: true
        dialect: org.hibernate.dialect.MySQLDialect

  flyway:
    locations: classpath:db/migration
    baseline-on-migrate: false

  kafka:
    bootstrap-servers: localhost:9092
    consumer:
      group-id: product-service
      auto-offset-reset: earliest
      enable-auto-commit: false

  profiles:
    active: local

server:
  port: 8084

jwt:
  secret: ${JWT_SECRET:default-dev-secret-key-must-be-at-least-256-bits-long-for-hmac-sha256}

kafka:
  topic:
    payment-cancelled: payment.cancelled

logging:
  level:
    com.example.product: INFO
```

- [ ] **Step 4: Flyway DDL 작성**

```sql
-- V1__create_product_core.sql

CREATE TABLE category (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    name       VARCHAR(50)  NOT NULL,
    parent_id  BIGINT       NULL,
    depth      INT          NOT NULL DEFAULT 0,
    created_at DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    INDEX idx_category_parent_id (parent_id),
    CONSTRAINT fk_category_parent FOREIGN KEY (parent_id) REFERENCES category(id)
);

CREATE TABLE product (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    merchant_id BIGINT       NOT NULL,
    category_id BIGINT       NOT NULL,
    status      VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    created_at  DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at  DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    INDEX idx_product_merchant_id (merchant_id),
    INDEX idx_product_category_id (category_id),
    CONSTRAINT fk_product_category FOREIGN KEY (category_id) REFERENCES category(id)
);

CREATE TABLE product_version (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    product_id     BIGINT         NOT NULL,
    name           VARCHAR(100)   NOT NULL,
    price          DECIMAL(19,2)  NOT NULL,
    discount_price DECIMAL(19,2)  NULL,
    attributes     JSON           NULL,
    version        INT            NOT NULL DEFAULT 1,
    is_current     BOOLEAN        NOT NULL DEFAULT TRUE,
    created_at     DATETIME(6)    NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    INDEX idx_product_version_product_id (product_id),
    CONSTRAINT fk_product_version_product FOREIGN KEY (product_id) REFERENCES product(id)
);

CREATE TABLE product_sku (
    id                 BIGINT AUTO_INCREMENT PRIMARY KEY,
    product_version_id BIGINT       NOT NULL,
    color              VARCHAR(30)  NOT NULL,
    size               VARCHAR(10)  NOT NULL,
    sku_code           VARCHAR(50)  NOT NULL UNIQUE,
    created_at         DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    INDEX idx_sku_product_version_id (product_version_id),
    CONSTRAINT fk_sku_product_version FOREIGN KEY (product_version_id) REFERENCES product_version(id)
);

CREATE TABLE product_stock (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    sku_id     BIGINT  NOT NULL UNIQUE,
    quantity   INT     NOT NULL DEFAULT 0,
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_stock_sku FOREIGN KEY (sku_id) REFERENCES product_sku(id)
);

CREATE TABLE processed_stock_event (
    id                BIGINT AUTO_INCREMENT PRIMARY KEY,
    cancel_request_id BIGINT NOT NULL UNIQUE,
    created_at        DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
);
```

- [ ] **Step 5: Dockerfile 작성**

```dockerfile
FROM gradle:8-jdk21 AS builder
WORKDIR /app
COPY build.gradle settings.gradle ./
COPY gradle gradle/
COPY payment-service/build.gradle          payment-service/
COPY order-service/build.gradle            order-service/
COPY merchant-limit-service/build.gradle   merchant-limit-service/
COPY risk-management-service/build.gradle  risk-management-service/
COPY product-service/build.gradle          product-service/
COPY user-service/build.gradle             user-service/
RUN gradle :product-service:dependencies --no-daemon -q
COPY product-service/src product-service/src
RUN gradle :product-service:bootJar --no-daemon -x test

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=builder /app/product-service/build/libs/*.jar app.jar
EXPOSE 8084
ENTRYPOINT ["java", "-jar", "app.jar"]
```

- [ ] **Step 6: 빌드 확인**

Run: `./gradlew :product-service:compileJava`
Expected: BUILD SUCCESSFUL

- [ ] **Step 7: 커밋**

```bash
git add product-service/ docker-compose.yml
git commit -m "feat(product): 모듈 스캐폴딩 — build.gradle, application.yml, Flyway DDL, Dockerfile"
```

---

## Task 2: Common 예외 계층 + 도메인 Enum

**Files:**
- Create: `common/exception/BusinessException.java`
- Create: `common/exception/ErrorCode.java`
- Create: `common/exception/domain/InsufficientStockException.java`
- Create: `common/exception/application/ProductNotFoundException.java`
- Create: `common/exception/application/SkuNotFoundException.java`
- Create: `common/exception/application/CategoryNotFoundException.java`
- Create: `common/exception/application/DuplicateSkuCodeException.java`
- Create: `common/exception/application/CategoryHasChildrenException.java`
- Create: `domain/entity/ProductStatus.java`

- [ ] **Step 1: BusinessException + ErrorCode 작성**

```java
// BusinessException.java
package com.example.product.common.exception;

import lombok.Getter;

@Getter
public abstract class BusinessException extends RuntimeException {
    private final ErrorCode errorCode;

    protected BusinessException(ErrorCode errorCode) {
        super(errorCode.getDefaultMessage());
        this.errorCode = errorCode;
    }

    protected BusinessException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }
}
```

```java
// ErrorCode.java
package com.example.product.common.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum ErrorCode {

    INVALID_REQUEST("INVALID_REQUEST", 400, "요청 형식이 올바르지 않습니다."),

    FORBIDDEN_PRODUCT("PROD_007", 403, "해당 상품에 대한 권한이 없습니다."),

    PRODUCT_NOT_FOUND("PROD_001", 404, "상품을 찾을 수 없습니다."),
    SKU_NOT_FOUND("PROD_002", 404, "SKU를 찾을 수 없습니다."),
    CATEGORY_NOT_FOUND("PROD_003", 404, "카테고리를 찾을 수 없습니다."),

    DUPLICATE_SKU_CODE("PROD_005", 409, "중복된 SKU 코드입니다."),

    INSUFFICIENT_STOCK("PROD_004", 422, "재고가 부족합니다."),
    CATEGORY_HAS_CHILDREN("PROD_006", 422, "하위 카테고리가 존재하여 삭제할 수 없습니다."),

    INTERNAL_ERROR("INTERNAL_ERROR", 500, "서버 오류가 발생했습니다.");

    private final String code;
    private final int httpStatus;
    private final String defaultMessage;
}
```

- [ ] **Step 2: 도메인/애플리케이션 예외 작성**

```java
// InsufficientStockException.java
package com.example.product.common.exception.domain;

import com.example.product.common.exception.BusinessException;
import com.example.product.common.exception.ErrorCode;
import lombok.Getter;

@Getter
public class InsufficientStockException extends BusinessException {
    private final long skuId;
    private final int requested;
    private final int available;

    public InsufficientStockException(long skuId, int requested, int available) {
        super(ErrorCode.INSUFFICIENT_STOCK,
              String.format("재고 부족 (skuId: %d, 요청: %d, 가용: %d)", skuId, requested, available));
        this.skuId = skuId;
        this.requested = requested;
        this.available = available;
    }
}
```

```java
// ProductNotFoundException.java
package com.example.product.common.exception.application;

import com.example.product.common.exception.BusinessException;
import com.example.product.common.exception.ErrorCode;

public class ProductNotFoundException extends BusinessException {
    public ProductNotFoundException(long productId) {
        super(ErrorCode.PRODUCT_NOT_FOUND,
              String.format("상품을 찾을 수 없습니다. (productId: %d)", productId));
    }
}
```

```java
// SkuNotFoundException.java
package com.example.product.common.exception.application;

import com.example.product.common.exception.BusinessException;
import com.example.product.common.exception.ErrorCode;

public class SkuNotFoundException extends BusinessException {
    public SkuNotFoundException(long skuId) {
        super(ErrorCode.SKU_NOT_FOUND,
              String.format("SKU를 찾을 수 없습니다. (skuId: %d)", skuId));
    }
}
```

```java
// CategoryNotFoundException.java
package com.example.product.common.exception.application;

import com.example.product.common.exception.BusinessException;
import com.example.product.common.exception.ErrorCode;

public class CategoryNotFoundException extends BusinessException {
    public CategoryNotFoundException(long categoryId) {
        super(ErrorCode.CATEGORY_NOT_FOUND,
              String.format("카테고리를 찾을 수 없습니다. (categoryId: %d)", categoryId));
    }
}
```

```java
// DuplicateSkuCodeException.java
package com.example.product.common.exception.application;

import com.example.product.common.exception.BusinessException;
import com.example.product.common.exception.ErrorCode;

public class DuplicateSkuCodeException extends BusinessException {
    public DuplicateSkuCodeException(String skuCode) {
        super(ErrorCode.DUPLICATE_SKU_CODE,
              String.format("중복된 SKU 코드입니다. (skuCode: %s)", skuCode));
    }
}
```

```java
// CategoryHasChildrenException.java
package com.example.product.common.exception.application;

import com.example.product.common.exception.BusinessException;
import com.example.product.common.exception.ErrorCode;

public class CategoryHasChildrenException extends BusinessException {
    public CategoryHasChildrenException(long categoryId) {
        super(ErrorCode.CATEGORY_HAS_CHILDREN,
              String.format("하위 카테고리가 존재하여 삭제할 수 없습니다. (categoryId: %d)", categoryId));
    }
}
```

- [ ] **Step 3: ProductStatus enum 작성**

```java
package com.example.product.domain.entity;

public enum ProductStatus {
    ACTIVE, INACTIVE;

    public boolean isActive() {
        return this == ACTIVE;
    }
}
```

- [ ] **Step 4: 컴파일 확인**

Run: `./gradlew :product-service:compileJava`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: 커밋**

```bash
git add product-service/src/main/java/com/example/product/common/ \
        product-service/src/main/java/com/example/product/domain/entity/ProductStatus.java
git commit -m "feat(product): ErrorCode, BusinessException, 예외 계층 + ProductStatus"
```

---

## Task 3: 도메인 엔티티 (Category, Product, ProductVersion) + TDD

**Files:**
- Create: `domain/entity/Category.java`, `Product.java`, `ProductVersion.java`
- Test: `domain/entity/CategoryTest.java`, `ProductTest.java`, `ProductVersionTest.java`

- [ ] **Step 1: 테스트 작성**

```java
// CategoryTest.java
package com.example.product.domain.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Category 도메인 엔티티")
class CategoryTest {

    @Test
    @DisplayName("루트 카테고리 생성 — depth 0, parentId null")
    void shouldCreateRootCategory() {
        Category category = Category.createRoot("의류");
        assertEquals("의류", category.getName());
        assertNull(category.getParentId());
        assertEquals(0, category.getDepth());
    }

    @Test
    @DisplayName("하위 카테고리 생성 — parentId 설정, depth = parent + 1")
    void shouldCreateChildCategory() {
        Category child = Category.createChild("티셔츠", 1L, 1);
        assertEquals("티셔츠", child.getName());
        assertEquals(1L, child.getParentId());
        assertEquals(2, child.getDepth());
    }

    @Test
    @DisplayName("카테고리명 수정")
    void shouldUpdateName() {
        Category category = Category.createRoot("의류");
        category.updateName("패션");
        assertEquals("패션", category.getName());
    }
}
```

```java
// ProductTest.java
package com.example.product.domain.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Product 도메인 엔티티")
class ProductTest {

    @Test
    @DisplayName("상품 생성 — ACTIVE 상태")
    void shouldCreateProduct() {
        Product product = Product.of(100L, 1L);
        assertEquals(100L, product.getMerchantId());
        assertEquals(1L, product.getCategoryId());
        assertEquals(ProductStatus.ACTIVE, product.getStatus());
    }

    @Test
    @DisplayName("상품 비활성화")
    void shouldDeactivate() {
        Product product = Product.of(100L, 1L);
        product.deactivate();
        assertEquals(ProductStatus.INACTIVE, product.getStatus());
    }

    @Test
    @DisplayName("상품 활성화")
    void shouldActivate() {
        Product product = Product.of(100L, 1L);
        product.deactivate();
        product.activate();
        assertEquals(ProductStatus.ACTIVE, product.getStatus());
    }
}
```

```java
// ProductVersionTest.java
package com.example.product.domain.entity;

import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ProductVersion 도메인 엔티티")
class ProductVersionTest {

    @Test
    @DisplayName("첫 버전 생성 — version=1, isCurrent=true")
    void shouldCreateFirstVersion() {
        ProductVersion v = ProductVersion.createFirst(1L, "반팔 티셔츠",
                BigDecimal.valueOf(29000), null, "{\"소재\":\"면100%\"}");
        assertEquals(1L, v.getProductId());
        assertEquals("반팔 티셔츠", v.getName());
        assertEquals(1, v.getVersion());
        assertTrue(v.isCurrent());
    }

    @Test
    @DisplayName("다음 버전 생성 — version 증가")
    void shouldCreateNextVersion() {
        ProductVersion v2 = ProductVersion.createNext(1L, "반팔 티셔츠 v2",
                BigDecimal.valueOf(25000), BigDecimal.valueOf(20000),
                "{\"소재\":\"면100%\"}", 1);
        assertEquals(2, v2.getVersion());
        assertTrue(v2.isCurrent());
    }

    @Test
    @DisplayName("현재 버전 해제")
    void shouldMarkNotCurrent() {
        ProductVersion v = ProductVersion.createFirst(1L, "상품", BigDecimal.valueOf(10000), null, null);
        v.markNotCurrent();
        assertFalse(v.isCurrent());
    }
}
```

- [ ] **Step 2: 테스트 실행 — 실패 확인**

Run: `./gradlew :product-service:test --tests "com.example.product.domain.entity.*" -i`
Expected: FAIL

- [ ] **Step 3: 도메인 엔티티 구현**

```java
// Category.java
package com.example.product.domain.entity;

import java.time.Instant;

public class Category {
    private Long id;
    private String name;
    private Long parentId;
    private int depth;
    private Instant createdAt;

    private Category(String name, Long parentId, int depth) {
        this.name = name;
        this.parentId = parentId;
        this.depth = depth;
        this.createdAt = Instant.now();
    }

    public static Category createRoot(String name) {
        return new Category(name, null, 0);
    }

    public static Category createChild(String name, long parentId, int parentDepth) {
        return new Category(name, parentId, parentDepth + 1);
    }

    public static Category reconstruct(Long id, String name, Long parentId, int depth, Instant createdAt) {
        Category c = new Category(name, parentId, depth);
        c.id = id;
        c.createdAt = createdAt;
        return c;
    }

    public void updateName(String name) {
        this.name = name;
    }

    public Long getId() { return id; }
    public String getName() { return name; }
    public Long getParentId() { return parentId; }
    public int getDepth() { return depth; }
    public Instant getCreatedAt() { return createdAt; }
}
```

```java
// Product.java
package com.example.product.domain.entity;

import java.time.Instant;

public class Product {
    private Long id;
    private long merchantId;
    private long categoryId;
    private ProductStatus status;
    private Instant createdAt;
    private Instant updatedAt;

    private Product(long merchantId, long categoryId) {
        this.merchantId = merchantId;
        this.categoryId = categoryId;
        this.status = ProductStatus.ACTIVE;
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    public static Product of(long merchantId, long categoryId) {
        return new Product(merchantId, categoryId);
    }

    public static Product reconstruct(Long id, long merchantId, long categoryId,
                                       ProductStatus status, Instant createdAt, Instant updatedAt) {
        Product p = new Product(merchantId, categoryId);
        p.id = id;
        p.status = status;
        p.createdAt = createdAt;
        p.updatedAt = updatedAt;
        return p;
    }

    public void activate() {
        this.status = ProductStatus.ACTIVE;
        this.updatedAt = Instant.now();
    }

    public void deactivate() {
        this.status = ProductStatus.INACTIVE;
        this.updatedAt = Instant.now();
    }

    public Long getId() { return id; }
    public long getMerchantId() { return merchantId; }
    public long getCategoryId() { return categoryId; }
    public ProductStatus getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
```

```java
// ProductVersion.java
package com.example.product.domain.entity;

import java.math.BigDecimal;
import java.time.Instant;

public class ProductVersion {
    private Long id;
    private long productId;
    private String name;
    private BigDecimal price;
    private BigDecimal discountPrice;
    private String attributes;
    private int version;
    private boolean isCurrent;
    private Instant createdAt;

    private ProductVersion(long productId, String name, BigDecimal price,
                           BigDecimal discountPrice, String attributes, int version) {
        this.productId = productId;
        this.name = name;
        this.price = price;
        this.discountPrice = discountPrice;
        this.attributes = attributes;
        this.version = version;
        this.isCurrent = true;
        this.createdAt = Instant.now();
    }

    public static ProductVersion createFirst(long productId, String name, BigDecimal price,
                                              BigDecimal discountPrice, String attributes) {
        return new ProductVersion(productId, name, price, discountPrice, attributes, 1);
    }

    public static ProductVersion createNext(long productId, String name, BigDecimal price,
                                             BigDecimal discountPrice, String attributes,
                                             int previousVersion) {
        return new ProductVersion(productId, name, price, discountPrice, attributes, previousVersion + 1);
    }

    public static ProductVersion reconstruct(Long id, long productId, String name, BigDecimal price,
                                              BigDecimal discountPrice, String attributes,
                                              int version, boolean isCurrent, Instant createdAt) {
        ProductVersion v = new ProductVersion(productId, name, price, discountPrice, attributes, version);
        v.id = id;
        v.isCurrent = isCurrent;
        v.createdAt = createdAt;
        return v;
    }

    public void markNotCurrent() {
        this.isCurrent = false;
    }

    public Long getId() { return id; }
    public long getProductId() { return productId; }
    public String getName() { return name; }
    public BigDecimal getPrice() { return price; }
    public BigDecimal getDiscountPrice() { return discountPrice; }
    public String getAttributes() { return attributes; }
    public int getVersion() { return version; }
    public boolean isCurrent() { return isCurrent; }
    public Instant getCreatedAt() { return createdAt; }
}
```

- [ ] **Step 4: 테스트 실행 — 통과 확인**

Run: `./gradlew :product-service:test --tests "com.example.product.domain.entity.*" -i`
Expected: PASS

- [ ] **Step 5: 커밋**

```bash
git add product-service/src/main/java/com/example/product/domain/entity/ \
        product-service/src/test/java/com/example/product/domain/entity/
git commit -m "feat(product): Category, Product, ProductVersion 도메인 엔티티 + TDD"
```

---

## Task 4: 도메인 엔티티 (ProductSku, ProductStock, ProcessedStockEvent) + TDD

**Files:**
- Create: `domain/entity/ProductSku.java`, `ProductStock.java`, `ProcessedStockEvent.java`
- Test: `domain/entity/ProductSkuTest.java`, `ProductStockTest.java`

- [ ] **Step 1: 테스트 작성**

```java
// ProductSkuTest.java
package com.example.product.domain.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ProductSku 도메인 엔티티")
class ProductSkuTest {

    @Test
    @DisplayName("SKU 생성 — skuCode 자동 생성")
    void shouldCreateSku() {
        ProductSku sku = ProductSku.of(1L, "빨강", "L", 100L);
        assertEquals(1L, sku.getProductVersionId());
        assertEquals("빨강", sku.getColor());
        assertEquals("L", sku.getSize());
        assertEquals("SKU-100-빨강-L", sku.getSkuCode());
    }
}
```

```java
// ProductStockTest.java
package com.example.product.domain.entity;

import com.example.product.common.exception.domain.InsufficientStockException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ProductStock 도메인 엔티티")
class ProductStockTest {

    @Test
    @DisplayName("재고 생성")
    void shouldCreate() {
        ProductStock stock = ProductStock.of(1L, 50);
        assertEquals(1L, stock.getSkuId());
        assertEquals(50, stock.getQuantity());
    }

    @Test
    @DisplayName("재고 차감 — 정상")
    void shouldDeduct() {
        ProductStock stock = ProductStock.of(1L, 50);
        stock.deduct(10);
        assertEquals(40, stock.getQuantity());
    }

    @Test
    @DisplayName("재고 차감 — 부족 시 InsufficientStockException")
    void shouldThrowWhenInsufficient() {
        ProductStock stock = ProductStock.of(1L, 5);
        assertThrows(InsufficientStockException.class, () -> stock.deduct(10));
    }

    @Test
    @DisplayName("재고 복원")
    void shouldRestore() {
        ProductStock stock = ProductStock.of(1L, 50);
        stock.deduct(10);
        stock.restore(10);
        assertEquals(50, stock.getQuantity());
    }

    @Test
    @DisplayName("재고 직접 수정")
    void shouldUpdateQuantity() {
        ProductStock stock = ProductStock.of(1L, 50);
        stock.updateQuantity(100);
        assertEquals(100, stock.getQuantity());
    }
}
```

- [ ] **Step 2: 테스트 실행 — 실패 확인**

Run: `./gradlew :product-service:test --tests "com.example.product.domain.entity.Product*" -i`
Expected: FAIL

- [ ] **Step 3: 도메인 엔티티 구현**

```java
// ProductSku.java
package com.example.product.domain.entity;

import java.time.Instant;

public class ProductSku {
    private Long id;
    private long productVersionId;
    private String color;
    private String size;
    private String skuCode;
    private Instant createdAt;

    private ProductSku(long productVersionId, String color, String size, String skuCode) {
        this.productVersionId = productVersionId;
        this.color = color;
        this.size = size;
        this.skuCode = skuCode;
        this.createdAt = Instant.now();
    }

    public static ProductSku of(long productVersionId, String color, String size, long productId) {
        String skuCode = String.format("SKU-%d-%s-%s", productId, color, size);
        return new ProductSku(productVersionId, color, size, skuCode);
    }

    public static ProductSku reconstruct(Long id, long productVersionId, String color,
                                          String size, String skuCode, Instant createdAt) {
        ProductSku sku = new ProductSku(productVersionId, color, size, skuCode);
        sku.id = id;
        sku.createdAt = createdAt;
        return sku;
    }

    public Long getId() { return id; }
    public long getProductVersionId() { return productVersionId; }
    public String getColor() { return color; }
    public String getSize() { return size; }
    public String getSkuCode() { return skuCode; }
    public Instant getCreatedAt() { return createdAt; }
}
```

```java
// ProductStock.java
package com.example.product.domain.entity;

import com.example.product.common.exception.domain.InsufficientStockException;
import java.time.Instant;

public class ProductStock {
    private Long id;
    private long skuId;
    private int quantity;
    private Instant updatedAt;

    private ProductStock(long skuId, int quantity) {
        this.skuId = skuId;
        this.quantity = quantity;
        this.updatedAt = Instant.now();
    }

    public static ProductStock of(long skuId, int quantity) {
        return new ProductStock(skuId, quantity);
    }

    public static ProductStock reconstruct(Long id, long skuId, int quantity, Instant updatedAt) {
        ProductStock s = new ProductStock(skuId, quantity);
        s.id = id;
        s.updatedAt = updatedAt;
        return s;
    }

    public void deduct(int amount) {
        if (this.quantity < amount) {
            throw new InsufficientStockException(skuId, amount, this.quantity);
        }
        this.quantity -= amount;
        this.updatedAt = Instant.now();
    }

    public void restore(int amount) {
        this.quantity += amount;
        this.updatedAt = Instant.now();
    }

    public void updateQuantity(int newQuantity) {
        this.quantity = newQuantity;
        this.updatedAt = Instant.now();
    }

    public Long getId() { return id; }
    public long getSkuId() { return skuId; }
    public int getQuantity() { return quantity; }
    public Instant getUpdatedAt() { return updatedAt; }
}
```

```java
// ProcessedStockEvent.java
package com.example.product.domain.entity;

import java.time.Instant;

public class ProcessedStockEvent {
    private Long id;
    private long cancelRequestId;
    private Instant createdAt;

    private ProcessedStockEvent(long cancelRequestId) {
        this.cancelRequestId = cancelRequestId;
        this.createdAt = Instant.now();
    }

    public static ProcessedStockEvent of(long cancelRequestId) {
        return new ProcessedStockEvent(cancelRequestId);
    }

    public static ProcessedStockEvent reconstruct(Long id, long cancelRequestId, Instant createdAt) {
        ProcessedStockEvent e = new ProcessedStockEvent(cancelRequestId);
        e.id = id;
        e.createdAt = createdAt;
        return e;
    }

    public Long getId() { return id; }
    public long getCancelRequestId() { return cancelRequestId; }
    public Instant getCreatedAt() { return createdAt; }
}
```

- [ ] **Step 4: 테스트 실행 — 통과 확인**

Run: `./gradlew :product-service:test --tests "com.example.product.domain.entity.*" -i`
Expected: PASS

- [ ] **Step 5: 커밋**

```bash
git add product-service/src/main/java/com/example/product/domain/entity/ \
        product-service/src/test/java/com/example/product/domain/entity/
git commit -m "feat(product): ProductSku, ProductStock, ProcessedStockEvent 도메인 엔티티 + TDD"
```

---

## Task 5: Repository 인터페이스

**Files:**
- Create: `application/interfaces/` — 6개 Repository 인터페이스

- [ ] **Step 1: 모든 인터페이스 작성**

```java
// CategoryRepository.java
package com.example.product.application.interfaces;

import com.example.product.domain.entity.Category;
import java.util.List;
import java.util.Optional;

public interface CategoryRepository {
    Category save(Category category);
    Optional<Category> findById(long id);
    List<Category> findAll();
    boolean existsByParentId(long parentId);
    void deleteById(long id);
}
```

```java
// ProductRepository.java
package com.example.product.application.interfaces;

import com.example.product.domain.entity.Product;
import java.util.List;
import java.util.Optional;

public interface ProductRepository {
    Product save(Product product);
    Optional<Product> findById(long id);
    List<Product> findAllByCategoryId(long categoryId);
    List<Product> findAllByMerchantId(long merchantId);
    List<Product> findAll();
}
```

```java
// ProductVersionRepository.java
package com.example.product.application.interfaces;

import com.example.product.domain.entity.ProductVersion;
import java.util.List;
import java.util.Optional;

public interface ProductVersionRepository {
    ProductVersion save(ProductVersion version);
    Optional<ProductVersion> findCurrentByProductId(long productId);
    List<ProductVersion> findAllByProductId(long productId);
}
```

```java
// ProductSkuRepository.java
package com.example.product.application.interfaces;

import com.example.product.domain.entity.ProductSku;
import java.util.List;
import java.util.Optional;

public interface ProductSkuRepository {
    ProductSku save(ProductSku sku);
    Optional<ProductSku> findById(long id);
    List<ProductSku> findAllByProductVersionId(long productVersionId);
    boolean existsBySkuCode(String skuCode);
}
```

```java
// ProductStockRepository.java
package com.example.product.application.interfaces;

import com.example.product.domain.entity.ProductStock;
import java.util.Optional;

public interface ProductStockRepository {
    ProductStock save(ProductStock stock);
    Optional<ProductStock> findBySkuId(long skuId);
    Optional<ProductStock> findBySkuIdForUpdate(long skuId);
}
```

```java
// ProcessedStockEventRepository.java
package com.example.product.application.interfaces;

import com.example.product.domain.entity.ProcessedStockEvent;

public interface ProcessedStockEventRepository {
    boolean existsByCancelRequestId(long cancelRequestId);
    ProcessedStockEvent save(ProcessedStockEvent event);
}
```

- [ ] **Step 2: 컴파일 확인**

Run: `./gradlew :product-service:compileJava`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: 커밋**

```bash
git add product-service/src/main/java/com/example/product/application/interfaces/
git commit -m "feat(product): Repository 인터페이스 6개 정의"
```

---

## Task 6: JPA 엔티티 + Repository 구현 + PersistenceConfig + Security

**Files:**
- Create: 6 JPA 엔티티, 6 JPA Repository, 6 RepositoryImpl, PersistenceConfig
- Create: JwtAuthenticationFilter, SecurityConfig, KafkaConsumerConfig
- Test: AbstractRepositoryTest

이 Task는 기계적 파일이 많으므로 user-service Task 9의 패턴을 그대로 따른다.

- [ ] **Step 1: 모든 JPA 엔티티 작성**

각 JPA 엔티티는 `from(DomainEntity)` + `toDomain()` 패턴을 따른다.
Instant ↔ LocalDateTime 변환은 ZoneOffset.UTC 기준.

CategoryJpaEntity, ProductJpaEntity, ProductVersionJpaEntity, ProductSkuJpaEntity, ProductStockJpaEntity, ProcessedStockEventJpaEntity — 총 6개.

`ProductStockJpaRepository`에는 `@Lock(LockModeType.PESSIMISTIC_WRITE)` + `@Query`로 `findBySkuIdForUpdate` 구현.

- [ ] **Step 2: Repository 구현체 6개 작성**

각 RepositoryImpl은 JpaRepository를 래핑하여 도메인 객체로 변환.

- [ ] **Step 3: PersistenceConfig 작성**

```java
package com.example.product.infrastructure.config;

import com.example.product.application.interfaces.*;
import com.example.product.infrastructure.persistence.*;
import jakarta.persistence.EntityManagerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

@Configuration
@EnableTransactionManagement
@EnableJpaRepositories(basePackages = "com.example.product.infrastructure.persistence")
public class PersistenceConfig {

    @Bean
    public PlatformTransactionManager transactionManager(EntityManagerFactory emf) {
        return new JpaTransactionManager(emf);
    }

    @Bean
    public CategoryRepository categoryRepository(CategoryJpaRepository jpa) {
        return new CategoryRepositoryImpl(jpa);
    }

    @Bean
    public ProductRepository productRepository(ProductJpaRepository jpa) {
        return new ProductRepositoryImpl(jpa);
    }

    @Bean
    public ProductVersionRepository productVersionRepository(ProductVersionJpaRepository jpa) {
        return new ProductVersionRepositoryImpl(jpa);
    }

    @Bean
    public ProductSkuRepository productSkuRepository(ProductSkuJpaRepository jpa) {
        return new ProductSkuRepositoryImpl(jpa);
    }

    @Bean
    public ProductStockRepository productStockRepository(ProductStockJpaRepository jpa) {
        return new ProductStockRepositoryImpl(jpa);
    }

    @Bean
    public ProcessedStockEventRepository processedStockEventRepository(ProcessedStockEventJpaRepository jpa) {
        return new ProcessedStockEventRepositoryImpl(jpa);
    }
}
```

- [ ] **Step 4: JwtAuthenticationFilter + SecurityConfig 작성**

payment-service의 JwtAuthenticationFilter 패턴과 동일. jjwt로 직접 파싱.
SecurityConfig: `/internal/**`와 `GET /v1/products/**`, `GET /v1/categories`는 permitAll. `/v1/categories/**` POST/PATCH/DELETE는 ADMIN만. 나머지는 authenticated.

- [ ] **Step 5: KafkaConsumerConfig 작성**

```java
package com.example.product.infrastructure.config;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.ContainerProperties;

import java.util.Map;

@Configuration
public class KafkaConsumerConfig {

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    @Bean
    public ConsumerFactory<String, String> consumerFactory() {
        return new DefaultKafkaConsumerFactory<>(Map.of(
            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
            ConsumerConfig.GROUP_ID_CONFIG, "product-service",
            ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
            ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
            ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
            ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false
        ));
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, String> kafkaListenerContainerFactory(
            ConsumerFactory<String, String> consumerFactory) {
        var factory = new ConcurrentKafkaListenerContainerFactory<String, String>();
        factory.setConsumerFactory(consumerFactory);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
        return factory;
    }
}
```

- [ ] **Step 6: AbstractRepositoryTest 작성**

```java
package com.example.product.infrastructure.persistence;

import com.example.product.infrastructure.config.PersistenceConfig;
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
            .withDatabaseName("product_test")
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
    }
}
```

- [ ] **Step 7: 컴파일 확인**

Run: `./gradlew :product-service:compileJava`
Expected: BUILD SUCCESSFUL

- [ ] **Step 8: 커밋**

```bash
git add product-service/src/main/java/com/example/product/infrastructure/ \
        product-service/src/test/java/com/example/product/infrastructure/
git commit -m "feat(product): JPA 엔티티, Repository 구현, PersistenceConfig, Security, KafkaConfig"
```

---

## Task 7: Application 레이어 — Category + Product + Version + SKU UseCase/Service + TDD

**Files:**
- Create: UseCase 인터페이스 4개, Service 구현체 4개
- Test: CategoryServiceTest, CreateProductServiceTest

- [ ] **Step 1: UseCase 인터페이스 작성**

```java
// CategoryUseCase.java
package com.example.product.application.usecase;

import com.example.product.domain.entity.Category;
import java.util.List;

public interface CategoryUseCase {
    Category create(String name, Long parentId);
    List<Category> getAll();
    Category update(long id, String name);
    void delete(long id);
}
```

```java
// CreateProductUseCase.java
package com.example.product.application.usecase;

import com.example.product.domain.entity.Product;
import com.example.product.domain.entity.ProductVersion;
import com.example.product.domain.entity.ProductSku;
import java.math.BigDecimal;
import java.util.List;

public interface CreateProductUseCase {
    record SkuInput(String color, String size, int quantity) {}
    record Command(long merchantId, long categoryId, String name, BigDecimal price,
                   BigDecimal discountPrice, String attributes, List<SkuInput> skus) {}
    record Result(Product product, ProductVersion version, List<ProductSku> skus) {}

    Result execute(Command command);
}
```

```java
// GetProductUseCase.java
package com.example.product.application.usecase;

import com.example.product.domain.entity.*;
import java.util.List;

public interface GetProductUseCase {
    record SkuWithStock(ProductSku sku, ProductStock stock) {}
    record DetailResult(Product product, ProductVersion currentVersion, List<SkuWithStock> skus) {}

    DetailResult getDetail(long productId);
    List<Product> list(Long categoryId, Long merchantId);
}
```

```java
// ProductVersionUseCase.java
package com.example.product.application.usecase;

import com.example.product.domain.entity.ProductVersion;
import java.math.BigDecimal;
import java.util.List;

public interface ProductVersionUseCase {
    record Command(String name, BigDecimal price, BigDecimal discountPrice, String attributes) {}

    ProductVersion createVersion(long productId, Command command);
    List<ProductVersion> getVersions(long productId);
}
```

```java
// SkuUseCase.java
package com.example.product.application.usecase;

import com.example.product.domain.entity.ProductSku;

public interface SkuUseCase {
    record CreateCommand(String color, String size, int initialQuantity) {}

    ProductSku addSku(long productId, CreateCommand command);
    void updateStock(long skuId, int quantity);
}
```

- [ ] **Step 2: CategoryServiceTest 작성**

```java
package com.example.product.application.service;

import com.example.product.application.interfaces.CategoryRepository;
import com.example.product.common.exception.application.CategoryHasChildrenException;
import com.example.product.common.exception.application.CategoryNotFoundException;
import com.example.product.domain.entity.Category;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("CategoryService")
class CategoryServiceTest {

    @Mock CategoryRepository categoryRepository;
    private CategoryService categoryService;

    @BeforeEach
    void setUp() { categoryService = new CategoryService(categoryRepository); }

    @Test
    @DisplayName("루트 카테고리 생성")
    void shouldCreateRootCategory() {
        when(categoryRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        Category result = categoryService.create("의류", null);
        assertEquals("의류", result.getName());
        assertEquals(0, result.getDepth());
    }

    @Test
    @DisplayName("하위 카테고리 생성 — 부모 depth + 1")
    void shouldCreateChildCategory() {
        Category parent = Category.reconstruct(1L, "의류", null, 0, null);
        when(categoryRepository.findById(1L)).thenReturn(Optional.of(parent));
        when(categoryRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        Category result = categoryService.create("상의", 1L);
        assertEquals(1L, result.getParentId());
        assertEquals(1, result.getDepth());
    }

    @Test
    @DisplayName("삭제 — 하위 카테고리 존재 시 예외")
    void shouldThrowWhenHasChildren() {
        when(categoryRepository.findById(1L)).thenReturn(Optional.of(
                Category.reconstruct(1L, "의류", null, 0, null)));
        when(categoryRepository.existsByParentId(1L)).thenReturn(true);

        assertThrows(CategoryHasChildrenException.class, () -> categoryService.delete(1L));
    }
}
```

- [ ] **Step 3: CreateProductServiceTest 작성**

```java
package com.example.product.application.service;

import com.example.product.application.interfaces.*;
import com.example.product.application.usecase.CreateProductUseCase.*;
import com.example.product.domain.entity.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("CreateProductService")
class CreateProductServiceTest {

    @Mock ProductRepository productRepository;
    @Mock ProductVersionRepository productVersionRepository;
    @Mock ProductSkuRepository productSkuRepository;
    @Mock ProductStockRepository productStockRepository;

    private CreateProductService service;

    @BeforeEach
    void setUp() {
        service = new CreateProductService(productRepository, productVersionRepository,
                productSkuRepository, productStockRepository);
    }

    @Test
    @DisplayName("상품 생성 — Product + Version + SKU + Stock 원자적 생성")
    void shouldCreateProductWithVersionAndSkus() {
        Product savedProduct = Product.reconstruct(1L, 100L, 1L,
                ProductStatus.ACTIVE, null, null);
        ProductVersion savedVersion = ProductVersion.reconstruct(10L, 1L, "상품",
                BigDecimal.valueOf(29000), null, null, 1, true, null);

        when(productRepository.save(any())).thenReturn(savedProduct);
        when(productVersionRepository.save(any())).thenReturn(savedVersion);
        when(productSkuRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(productStockRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        Result result = service.execute(new Command(
                100L, 1L, "상품", BigDecimal.valueOf(29000), null, null,
                List.of(new SkuInput("빨강", "L", 50))));

        assertNotNull(result.product());
        assertEquals(1, result.skus().size());
        verify(productStockRepository).save(any());
    }
}
```

- [ ] **Step 4: Service 구현체 작성**

```java
// CategoryService.java
package com.example.product.application.service;

import com.example.product.application.interfaces.CategoryRepository;
import com.example.product.application.usecase.CategoryUseCase;
import com.example.product.common.exception.application.CategoryHasChildrenException;
import com.example.product.common.exception.application.CategoryNotFoundException;
import com.example.product.domain.entity.Category;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class CategoryService implements CategoryUseCase {
    private final CategoryRepository categoryRepository;

    @Override
    @Transactional
    public Category create(String name, Long parentId) {
        if (parentId == null) {
            return categoryRepository.save(Category.createRoot(name));
        }
        Category parent = categoryRepository.findById(parentId)
                .orElseThrow(() -> new CategoryNotFoundException(parentId));
        return categoryRepository.save(Category.createChild(name, parentId, parent.getDepth()));
    }

    @Override
    @Transactional(readOnly = true)
    public List<Category> getAll() { return categoryRepository.findAll(); }

    @Override
    @Transactional
    public Category update(long id, String name) {
        Category category = categoryRepository.findById(id)
                .orElseThrow(() -> new CategoryNotFoundException(id));
        category.updateName(name);
        return categoryRepository.save(category);
    }

    @Override
    @Transactional
    public void delete(long id) {
        categoryRepository.findById(id)
                .orElseThrow(() -> new CategoryNotFoundException(id));
        if (categoryRepository.existsByParentId(id)) {
            throw new CategoryHasChildrenException(id);
        }
        categoryRepository.deleteById(id);
    }
}
```

```java
// CreateProductService.java
package com.example.product.application.service;

import com.example.product.application.interfaces.*;
import com.example.product.application.usecase.CreateProductUseCase;
import com.example.product.domain.entity.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class CreateProductService implements CreateProductUseCase {
    private final ProductRepository productRepository;
    private final ProductVersionRepository productVersionRepository;
    private final ProductSkuRepository productSkuRepository;
    private final ProductStockRepository productStockRepository;

    @Override
    @Transactional
    public Result execute(Command command) {
        Product product = productRepository.save(Product.of(command.merchantId(), command.categoryId()));

        ProductVersion version = productVersionRepository.save(
                ProductVersion.createFirst(product.getId(), command.name(),
                        command.price(), command.discountPrice(), command.attributes()));

        List<ProductSku> skus = new ArrayList<>();
        for (SkuInput input : command.skus()) {
            ProductSku sku = productSkuRepository.save(
                    ProductSku.of(version.getId(), input.color(), input.size(), product.getId()));
            productStockRepository.save(ProductStock.of(sku.getId(), input.quantity()));
            skus.add(sku);
        }

        return new Result(product, version, skus);
    }
}
```

```java
// GetProductService.java
package com.example.product.application.service;

import com.example.product.application.interfaces.*;
import com.example.product.application.usecase.GetProductUseCase;
import com.example.product.common.exception.application.ProductNotFoundException;
import com.example.product.domain.entity.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class GetProductService implements GetProductUseCase {
    private final ProductRepository productRepository;
    private final ProductVersionRepository productVersionRepository;
    private final ProductSkuRepository productSkuRepository;
    private final ProductStockRepository productStockRepository;

    @Override
    @Transactional(readOnly = true)
    public DetailResult getDetail(long productId) {
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new ProductNotFoundException(productId));
        ProductVersion version = productVersionRepository.findCurrentByProductId(productId)
                .orElseThrow(() -> new ProductNotFoundException(productId));
        List<ProductSku> skus = productSkuRepository.findAllByProductVersionId(version.getId());
        List<SkuWithStock> skuWithStocks = skus.stream()
                .map(sku -> new SkuWithStock(sku, productStockRepository.findBySkuId(sku.getId()).orElse(null)))
                .toList();
        return new DetailResult(product, version, skuWithStocks);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Product> list(Long categoryId, Long merchantId) {
        if (categoryId != null) return productRepository.findAllByCategoryId(categoryId);
        if (merchantId != null) return productRepository.findAllByMerchantId(merchantId);
        return productRepository.findAll();
    }
}
```

```java
// ProductVersionService.java
package com.example.product.application.service;

import com.example.product.application.interfaces.ProductRepository;
import com.example.product.application.interfaces.ProductVersionRepository;
import com.example.product.application.usecase.ProductVersionUseCase;
import com.example.product.common.exception.application.ProductNotFoundException;
import com.example.product.domain.entity.Product;
import com.example.product.domain.entity.ProductVersion;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class ProductVersionService implements ProductVersionUseCase {
    private final ProductRepository productRepository;
    private final ProductVersionRepository productVersionRepository;

    @Override
    @Transactional
    public ProductVersion createVersion(long productId, Command command) {
        productRepository.findById(productId)
                .orElseThrow(() -> new ProductNotFoundException(productId));
        ProductVersion current = productVersionRepository.findCurrentByProductId(productId).orElse(null);
        int previousVersion = current != null ? current.getVersion() : 0;
        if (current != null) {
            current.markNotCurrent();
            productVersionRepository.save(current);
        }
        return productVersionRepository.save(ProductVersion.createNext(
                productId, command.name(), command.price(),
                command.discountPrice(), command.attributes(), previousVersion));
    }

    @Override
    @Transactional(readOnly = true)
    public List<ProductVersion> getVersions(long productId) {
        productRepository.findById(productId)
                .orElseThrow(() -> new ProductNotFoundException(productId));
        return productVersionRepository.findAllByProductId(productId);
    }
}
```

```java
// SkuService.java
package com.example.product.application.service;

import com.example.product.application.interfaces.*;
import com.example.product.application.usecase.SkuUseCase;
import com.example.product.common.exception.application.ProductNotFoundException;
import com.example.product.common.exception.application.SkuNotFoundException;
import com.example.product.domain.entity.ProductSku;
import com.example.product.domain.entity.ProductStock;
import com.example.product.domain.entity.ProductVersion;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class SkuService implements SkuUseCase {
    private final ProductRepository productRepository;
    private final ProductVersionRepository productVersionRepository;
    private final ProductSkuRepository productSkuRepository;
    private final ProductStockRepository productStockRepository;

    @Override
    @Transactional
    public ProductSku addSku(long productId, CreateCommand command) {
        productRepository.findById(productId)
                .orElseThrow(() -> new ProductNotFoundException(productId));
        ProductVersion version = productVersionRepository.findCurrentByProductId(productId)
                .orElseThrow(() -> new ProductNotFoundException(productId));
        ProductSku sku = productSkuRepository.save(
                ProductSku.of(version.getId(), command.color(), command.size(), productId));
        productStockRepository.save(ProductStock.of(sku.getId(), command.initialQuantity()));
        return sku;
    }

    @Override
    @Transactional
    public void updateStock(long skuId, int quantity) {
        ProductStock stock = productStockRepository.findBySkuId(skuId)
                .orElseThrow(() -> new SkuNotFoundException(skuId));
        stock.updateQuantity(quantity);
        productStockRepository.save(stock);
    }
}
```

- [ ] **Step 5: 테스트 실행**

Run: `./gradlew :product-service:test --tests "com.example.product.application.service.*" -i`
Expected: PASS

- [ ] **Step 6: 커밋**

```bash
git add product-service/src/main/java/com/example/product/application/ \
        product-service/src/test/java/com/example/product/application/
git commit -m "feat(product): Category/Product/Version/SKU UseCase + Service + TDD"
```

---

## Task 8: Application 레이어 — Stock Deduct + Restore UseCase/Service + TDD

**Files:**
- Create: `application/usecase/StockDeductUseCase.java`, `StockRestoreUseCase.java`
- Create: `application/service/StockDeductService.java`, `StockRestoreService.java`
- Test: `StockDeductServiceTest.java`, `StockRestoreServiceTest.java`

- [ ] **Step 1: UseCase 인터페이스 작성**

```java
// StockDeductUseCase.java
package com.example.product.application.usecase;

public interface StockDeductUseCase {
    record Command(long skuId, int quantity) {}
    record Result(long skuId, int remainingQuantity) {}

    Result execute(Command command);
}
```

```java
// StockRestoreUseCase.java
package com.example.product.application.usecase;

import java.util.List;

public interface StockRestoreUseCase {
    record CancelledItem(long skuId, int quantity) {}
    record Command(long cancelRequestId, List<CancelledItem> items) {}

    void execute(Command command);
}
```

- [ ] **Step 2: StockDeductServiceTest 작성**

```java
package com.example.product.application.service;

import com.example.product.application.interfaces.ProductStockRepository;
import com.example.product.application.usecase.StockDeductUseCase.*;
import com.example.product.common.exception.application.SkuNotFoundException;
import com.example.product.common.exception.domain.InsufficientStockException;
import com.example.product.domain.entity.ProductStock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("StockDeductService")
class StockDeductServiceTest {

    @Mock ProductStockRepository productStockRepository;
    private StockDeductService service;

    @BeforeEach
    void setUp() { service = new StockDeductService(productStockRepository); }

    @Test
    @DisplayName("정상 차감")
    void shouldDeduct() {
        ProductStock stock = ProductStock.reconstruct(1L, 5L, 50, null);
        when(productStockRepository.findBySkuIdForUpdate(5L)).thenReturn(Optional.of(stock));
        when(productStockRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        Result result = service.execute(new Command(5L, 10));
        assertEquals(40, result.remainingQuantity());
    }

    @Test
    @DisplayName("재고 부족 → InsufficientStockException")
    void shouldThrowWhenInsufficient() {
        ProductStock stock = ProductStock.reconstruct(1L, 5L, 3, null);
        when(productStockRepository.findBySkuIdForUpdate(5L)).thenReturn(Optional.of(stock));

        assertThrows(InsufficientStockException.class, () -> service.execute(new Command(5L, 10)));
    }

    @Test
    @DisplayName("SKU 없음 → SkuNotFoundException")
    void shouldThrowWhenSkuNotFound() {
        when(productStockRepository.findBySkuIdForUpdate(99L)).thenReturn(Optional.empty());
        assertThrows(SkuNotFoundException.class, () -> service.execute(new Command(99L, 1)));
    }
}
```

- [ ] **Step 3: StockRestoreServiceTest 작성**

```java
package com.example.product.application.service;

import com.example.product.application.interfaces.ProcessedStockEventRepository;
import com.example.product.application.interfaces.ProductStockRepository;
import com.example.product.application.usecase.StockRestoreUseCase.*;
import com.example.product.domain.entity.ProductStock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("StockRestoreService")
class StockRestoreServiceTest {

    @Mock ProductStockRepository productStockRepository;
    @Mock ProcessedStockEventRepository processedStockEventRepository;
    private StockRestoreService service;

    @BeforeEach
    void setUp() { service = new StockRestoreService(productStockRepository, processedStockEventRepository); }

    @Test
    @DisplayName("정상 복원 — 재고 증가 + 이벤트 기록")
    void shouldRestore() {
        when(processedStockEventRepository.existsByCancelRequestId(1L)).thenReturn(false);
        ProductStock stock = ProductStock.reconstruct(1L, 5L, 40, null);
        when(productStockRepository.findBySkuId(5L)).thenReturn(Optional.of(stock));
        when(productStockRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        service.execute(new Command(1L, List.of(new CancelledItem(5L, 10))));

        verify(productStockRepository).save(argThat(s -> s.getQuantity() == 50));
        verify(processedStockEventRepository).save(any());
    }

    @Test
    @DisplayName("중복 이벤트 — skip")
    void shouldSkipDuplicate() {
        when(processedStockEventRepository.existsByCancelRequestId(1L)).thenReturn(true);

        service.execute(new Command(1L, List.of(new CancelledItem(5L, 10))));

        verify(productStockRepository, never()).save(any());
    }
}
```

- [ ] **Step 4: Service 구현**

```java
// StockDeductService.java
package com.example.product.application.service;

import com.example.product.application.interfaces.ProductStockRepository;
import com.example.product.application.usecase.StockDeductUseCase;
import com.example.product.common.exception.application.SkuNotFoundException;
import com.example.product.domain.entity.ProductStock;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class StockDeductService implements StockDeductUseCase {
    private final ProductStockRepository productStockRepository;

    @Override
    @Transactional
    public Result execute(Command command) {
        ProductStock stock = productStockRepository.findBySkuIdForUpdate(command.skuId())
                .orElseThrow(() -> new SkuNotFoundException(command.skuId()));
        stock.deduct(command.quantity());
        productStockRepository.save(stock);
        return new Result(command.skuId(), stock.getQuantity());
    }
}
```

```java
// StockRestoreService.java
package com.example.product.application.service;

import com.example.product.application.interfaces.ProcessedStockEventRepository;
import com.example.product.application.interfaces.ProductStockRepository;
import com.example.product.application.usecase.StockRestoreUseCase;
import com.example.product.domain.entity.ProcessedStockEvent;
import com.example.product.domain.entity.ProductStock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class StockRestoreService implements StockRestoreUseCase {
    private final ProductStockRepository productStockRepository;
    private final ProcessedStockEventRepository processedStockEventRepository;

    @Override
    @Transactional
    public void execute(Command command) {
        if (processedStockEventRepository.existsByCancelRequestId(command.cancelRequestId())) {
            log.info("이미 처리된 재고 복원 이벤트. cancelRequestId={}", command.cancelRequestId());
            return;
        }

        for (CancelledItem item : command.items()) {
            productStockRepository.findBySkuId(item.skuId())
                    .ifPresent(stock -> {
                        stock.restore(item.quantity());
                        productStockRepository.save(stock);
                    });
        }

        processedStockEventRepository.save(ProcessedStockEvent.of(command.cancelRequestId()));
    }
}
```

- [ ] **Step 5: 테스트 실행**

Run: `./gradlew :product-service:test --tests "com.example.product.application.service.*" -i`
Expected: PASS

- [ ] **Step 6: 커밋**

```bash
git add product-service/src/main/java/com/example/product/application/ \
        product-service/src/test/java/com/example/product/application/
git commit -m "feat(product): StockDeduct/StockRestore UseCase + Service + TDD"
```

---

## Task 9: Kafka Consumer + 테스트

**Files:**
- Create: `infrastructure/messaging/PaymentCancelledStockConsumer.java`
- Test: `infrastructure/messaging/PaymentCancelledStockConsumerTest.java`

- [ ] **Step 1: Consumer 테스트 작성**

```java
package com.example.product.infrastructure.messaging;

import com.example.product.application.usecase.StockRestoreUseCase;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.support.Acknowledgment;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("PaymentCancelledStockConsumer")
class PaymentCancelledStockConsumerTest {

    @Mock StockRestoreUseCase stockRestoreUseCase;
    @Mock Acknowledgment acknowledgment;

    private PaymentCancelledStockConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new PaymentCancelledStockConsumer(stockRestoreUseCase, new ObjectMapper());
    }

    @Test
    @DisplayName("정상 메시지 처리 → ACK")
    void shouldProcessAndAck() {
        String payload = """
            {"cancelRequestId":1,"cancelledItems":[{"skuId":5,"quantity":2}]}
            """;
        ConsumerRecord<String, String> record = new ConsumerRecord<>("payment.cancelled", 0, 0, "key", payload);

        consumer.consume(record, acknowledgment);

        verify(stockRestoreUseCase).execute(any());
        verify(acknowledgment).acknowledge();
    }

    @Test
    @DisplayName("JSON 파싱 실패 → ACK (DLQ 없이 skip)")
    void shouldAckOnParseError() {
        ConsumerRecord<String, String> record = new ConsumerRecord<>("payment.cancelled", 0, 0, "key", "invalid");

        consumer.consume(record, acknowledgment);

        verify(stockRestoreUseCase, never()).execute(any());
        verify(acknowledgment).acknowledge();
    }
}
```

- [ ] **Step 2: Consumer 구현**

```java
package com.example.product.infrastructure.messaging;

import com.example.product.application.usecase.StockRestoreUseCase;
import com.example.product.application.usecase.StockRestoreUseCase.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentCancelledStockConsumer {

    private final StockRestoreUseCase stockRestoreUseCase;
    private final ObjectMapper objectMapper;

    @KafkaListener(topics = "${kafka.topic.payment-cancelled}", groupId = "product-service")
    public void consume(ConsumerRecord<String, String> record, Acknowledgment ack) {
        try {
            JsonNode root = objectMapper.readTree(record.value());
            long cancelRequestId = root.get("cancelRequestId").asLong();

            List<CancelledItem> items = new ArrayList<>();
            for (JsonNode item : root.get("cancelledItems")) {
                items.add(new CancelledItem(
                        item.get("skuId").asLong(),
                        item.get("quantity").asInt()));
            }

            stockRestoreUseCase.execute(new Command(cancelRequestId, items));
        } catch (Exception e) {
            log.error("payment.cancelled 메시지 처리 실패. offset={}", record.offset(), e);
        } finally {
            ack.acknowledge();
        }
    }
}
```

- [ ] **Step 3: 테스트 실행**

Run: `./gradlew :product-service:test --tests "com.example.product.infrastructure.messaging.*" -i`
Expected: PASS

- [ ] **Step 4: 커밋**

```bash
git add product-service/src/main/java/com/example/product/infrastructure/messaging/ \
        product-service/src/test/java/com/example/product/infrastructure/messaging/
git commit -m "feat(product): PaymentCancelledStockConsumer Kafka Consumer + TDD"
```

---

## Task 10: Presentation 레이어 (Controllers + DTOs + GlobalExceptionHandler)

**Files:**
- Create: DTO 14개, Controller 5개, GlobalExceptionHandler

- [ ] **Step 1: GlobalExceptionHandler 작성**

```java
package com.example.product.presentation;

import com.example.product.common.exception.BusinessException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Map<String, Object>> handleBusiness(BusinessException e) {
        return ResponseEntity
                .status(e.getErrorCode().getHttpStatus())
                .body(Map.of(
                        "code", e.getErrorCode().getCode(),
                        "message", e.getMessage()));
    }
}
```

- [ ] **Step 2: DTO 작성**

모든 DTO는 Java record로 작성. 유효성 검증 어노테이션 포함.

CreateCategoryRequest, UpdateCategoryRequest, CategoryResponse, CreateProductRequest (skus 포함), ProductDetailResponse, ProductListResponse, UpdateProductStatusRequest, CreateVersionRequest, VersionResponse, CreateSkuRequest, UpdateStockRequest, StockDeductRequest, StockDeductResponse, InternalProductResponse — 총 14개.

- [ ] **Step 3: CategoryController 작성**

ADMIN 전용. `POST/GET/PATCH/DELETE /v1/categories`.

- [ ] **Step 4: ProductController 작성**

`POST /v1/products` (MERCHANT), `GET /v1/products/{id}`, `GET /v1/products`, `PATCH /v1/products/{id}/status` (MERCHANT), `POST /v1/products/{id}/versions` (MERCHANT), `GET /v1/products/{id}/versions`, `POST /v1/products/{id}/skus` (MERCHANT), `PATCH /v1/skus/{skuId}/stock` (MERCHANT).

productController에서 MERCHANT 인가 검증: `JWT.merchantId == Product.merchantId`.

- [ ] **Step 5: InternalProductController + InternalStockController 작성**

`GET /internal/products/{id}` — 상품 정보 + 현재 버전 반환.
`POST /internal/stocks/deduct` — 재고 차감.

- [ ] **Step 6: 컴파일 확인**

Run: `./gradlew :product-service:compileJava`
Expected: BUILD SUCCESSFUL

- [ ] **Step 7: 커밋**

```bash
git add product-service/src/main/java/com/example/product/presentation/
git commit -m "feat(product): Controller + DTO + GlobalExceptionHandler"
```

---

## Task 11: Docker Compose + 전체 빌드 검증

**Files:**
- Modify: `docker-compose.yml`

- [ ] **Step 1: docker-compose.yml에 product-service 앱 서비스 추가**

mysql-product은 이미 존재 (port 3310). 앱 서비스만 추가:

```yaml
  product-service:
    build:
      context: .
      dockerfile: product-service/Dockerfile
    ports:
      - "8084:8084"
    environment:
      SPRING_DATASOURCE_URL: jdbc:mysql://mysql-product:3306/product_db?serverTimezone=UTC&useSSL=false&allowPublicKeyRetrieval=true
      SPRING_DATASOURCE_USERNAME: product
      SPRING_DATASOURCE_PASSWORD: product
      SPRING_KAFKA_BOOTSTRAP_SERVERS: kafka1:29092,kafka2:29093,kafka3:29094
      JWT_SECRET: shared-jwt-secret-key-must-be-at-least-256-bits-long-for-hmac-sha256
    depends_on:
      - mysql-product
      - kafka1
      - kafka2
      - kafka3
```

- [ ] **Step 2: 전체 테스트**

Run: `./gradlew :product-service:test`
Expected: PASS (Testcontainers 통합 테스트는 Docker 필요)

- [ ] **Step 3: 전체 빌드**

Run: `./gradlew compileJava`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: 커밋**

```bash
git add docker-compose.yml
git commit -m "feat(product): docker-compose에 product-service 앱 서비스 추가"
```
