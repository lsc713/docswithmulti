# 체크아웃 P2 (서버 장바구니) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** order-service에 유저별 서버 장바구니(`cart_item` + CRUD API)를 추가하고, 게이트웨이로 노출하고, 스토어프론트에 장바구니 담기·조회·수정·주문(기존 Checkout 재사용)을 붙인다.

**Architecture:** 장바구니는 order-service의 독립 `cart_item` 테이블 + `CartController(/v1/cart)`(기존 order 생성/검증·취소 코어 무변경). 게이트웨이 `/v1/cart/**` 인증 라우트로 order downstream에 전달. 프론트는 서버 장바구니를 조회/변경하고, 체크아웃은 P1 `Checkout`(order→payment)을 그대로 재사용, 결제 성공 시 장바구니를 비운다.

**Tech Stack:** Java 21 · Spring Boot 4 · Spring Data JPA · Flyway · JUnit5 + Mockito(MockMvc) + Testcontainers · React 19 · Vite · Playwright.

## Global Constraints

- 도메인 레이어(`domain/**`)에 Spring/JPA 어노테이션 금지 — POJO + lombok `@Getter`만(기존 `OrderItem` 패턴).
- 취소 코어 · `POST /v1/orders` · `items:verify` · order/order_item 테이블·로직 무변경. cart는 독립 테이블/컨트롤러.
- 장바구니 API는 전부 `@RequestHeader("X-User-Id") long userId`로만 스코프(다른 유저 접근 불가). 역할 무관.
- 금액 규약(P1 확정, 무변경): 체크아웃은 `itemAmount`/order `price` = 단가 × 수량, `totalAmount = Σ itemAmount`.
- 프론트 변경 호출은 `api.js`의 `req(path,{csrf:true})` 재사용. 스토어프론트 어드민(`admin.html`, `src/admin/*`) 무변경. P1 `Checkout.jsx`/`OrderSuccess.jsx`는 재사용(무변경).
- order-service Flyway 최신은 V4 → 다음은 **V5**. DDL 스타일은 기존 마이그레이션과 동일(`BIGINT AUTO_INCREMENT`, `DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3)`).
- 프론트엔드엔 컴포넌트 단위 테스트 러너 없음(oxlint + Playwright E2E). 프론트 검증은 dev 로드 + E2E.

---

## File Structure

**백엔드 (order-service)**
- Create: `resources/db/migration/V5__create_cart.sql`
- Create: `domain/entity/CartItem.java`
- Create: `application/interfaces/CartRepository.java`
- Create: `application/usecase/CartUseCase.java`
- Create: `application/service/CartService.java`
- Create: `domain/exception/CartItemNotFoundException.java` (BusinessException 하위는 domain/exception/에 위치 — 기존 관례)
- Modify: `common/exception/ErrorCode.java` — `CART_ITEM_NOT_FOUND`
- Create: `infrastructure/persistence/CartItemJpaEntity.java`
- Create: `infrastructure/persistence/CartItemJpaRepository.java`
- Create: `infrastructure/persistence/CartRepositoryImpl.java`
- Modify: `infrastructure/config/PersistenceConfig.java` — `cartRepository` @Bean
- Create: `presentation/controller/CartController.java`
- Create: `presentation/dto/{AddCartItemRequest,UpdateQuantityRequest,CartResponse}.java`
- Test: `CartServiceTest`(단위), `CartRepositoryImplTest`(통합), `CartControllerTest`(MockMvc)

**게이트웨이**
- Modify: `api-gateway/.../config/RouteConfig.java` — `cartRoute`
- Test: `api-gateway/.../integration/GatewayRoutingIT.java` — cart 라우트 2 테스트

**프론트 (frontend/src)**
- Modify: `api.js` — getCart/addCartItem/updateCartItem/removeCartItem/clearCart
- Create: `components/Cart.jsx`
- Modify: `components/ProductDetail.jsx` — "장바구니 담기" 버튼
- Modify: `components/NavBar.jsx` — 장바구니(n) 버튼
- Modify: `App.jsx` — cart 상태·뷰 배선
- Modify: `App.css` — cart 스타일
- Test: `e2e/cart.spec.js`

---

## Task 1: 백엔드 — cart 영속 계층 (도메인·레포·마이그레이션)

**Files:**
- Create: `order-service/src/main/resources/db/migration/V5__create_cart.sql`
- Create: `order-service/src/main/java/com/example/order/domain/entity/CartItem.java`
- Create: `order-service/src/main/java/com/example/order/application/interfaces/CartRepository.java`
- Create: `order-service/src/main/java/com/example/order/infrastructure/persistence/CartItemJpaEntity.java`
- Create: `order-service/src/main/java/com/example/order/infrastructure/persistence/CartItemJpaRepository.java`
- Create: `order-service/src/main/java/com/example/order/infrastructure/persistence/CartRepositoryImpl.java`
- Modify: `order-service/src/main/java/com/example/order/infrastructure/config/PersistenceConfig.java`
- Test: `order-service/src/test/java/com/example/order/infrastructure/persistence/CartRepositoryImplTest.java`

**Interfaces:**
- Produces:
  - `CartItem`(domain): `id, userId, skuId, productId, itemName, optionSummary, unitPrice(long), quantity(int)`; `create(userId,skuId,productId,itemName,optionSummary,unitPrice,quantity)`(id=0), `of(...전체...)`, `changeQuantity(int)`.
  - `CartRepository`: `List<CartItem> findByUserId(long)`, `Optional<CartItem> findByUserIdAndSkuId(long,long)`, `CartItem save(CartItem)`, `void deleteByUserIdAndSkuId(long,long)`, `void deleteByUserId(long)`.

- [ ] **Step 1: V5 마이그레이션**

`V5__create_cart.sql`:
```sql
CREATE TABLE cart_item (
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    user_id        BIGINT       NOT NULL,
    sku_id         BIGINT       NOT NULL,
    product_id     BIGINT       NOT NULL,
    item_name      VARCHAR(255) NOT NULL,
    option_summary VARCHAR(255) NULL,
    unit_price     BIGINT       NOT NULL,
    quantity       INT          NOT NULL,
    created_at     DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at     DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_cart_user_sku (user_id, sku_id)
);
```

- [ ] **Step 2: CartItem 도메인 (POJO, JPA 어노테이션 금지)**

`domain/entity/CartItem.java`:
```java
package com.example.order.domain.entity;

import lombok.Getter;

@Getter
public class CartItem {

    private final long id;
    private final long userId;
    private final long skuId;
    private final long productId;
    private final String itemName;
    private final String optionSummary;
    private final long unitPrice;
    private int quantity;

    private CartItem(long id, long userId, long skuId, long productId, String itemName,
                     String optionSummary, long unitPrice, int quantity) {
        this.id = id; this.userId = userId; this.skuId = skuId; this.productId = productId;
        this.itemName = itemName; this.optionSummary = optionSummary;
        this.unitPrice = unitPrice; this.quantity = quantity;
    }

    public static CartItem create(long userId, long skuId, long productId, String itemName,
                                  String optionSummary, long unitPrice, int quantity) {
        return new CartItem(0, userId, skuId, productId, itemName, optionSummary, unitPrice, quantity);
    }

    public static CartItem of(long id, long userId, long skuId, long productId, String itemName,
                              String optionSummary, long unitPrice, int quantity) {
        return new CartItem(id, userId, skuId, productId, itemName, optionSummary, unitPrice, quantity);
    }

    public void changeQuantity(int quantity) { this.quantity = quantity; }
}
```

- [ ] **Step 3: CartRepository 포트**

`application/interfaces/CartRepository.java`:
```java
package com.example.order.application.interfaces;

import com.example.order.domain.entity.CartItem;
import java.util.List;
import java.util.Optional;

public interface CartRepository {
    List<CartItem> findByUserId(long userId);
    Optional<CartItem> findByUserIdAndSkuId(long userId, long skuId);
    CartItem save(CartItem item);
    void deleteByUserIdAndSkuId(long userId, long skuId);
    void deleteByUserId(long userId);
}
```

- [ ] **Step 4: JPA 엔티티 + 레포 + Impl**

`infrastructure/persistence/CartItemJpaEntity.java`:
```java
package com.example.order.infrastructure.persistence;

import com.example.order.domain.entity.CartItem;
import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "cart_item")
public class CartItemJpaEntity {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "user_id", nullable = false) private Long userId;
    @Column(name = "sku_id", nullable = false) private Long skuId;
    @Column(name = "product_id", nullable = false) private Long productId;
    @Column(name = "item_name", nullable = false, length = 255) private String itemName;
    @Column(name = "option_summary", length = 255) private String optionSummary;
    @Column(name = "unit_price", nullable = false) private Long unitPrice;
    @Column(nullable = false) private Integer quantity;
    @Column(nullable = false, updatable = false) private Instant createdAt;
    @Column(nullable = false) private Instant updatedAt;

    protected CartItemJpaEntity() {}

    static CartItemJpaEntity forInsert(CartItem c) {
        CartItemJpaEntity e = new CartItemJpaEntity();
        e.userId = c.getUserId(); e.skuId = c.getSkuId(); e.productId = c.getProductId();
        e.itemName = c.getItemName(); e.optionSummary = c.getOptionSummary();
        e.unitPrice = c.getUnitPrice(); e.quantity = c.getQuantity();
        e.createdAt = Instant.now(); e.updatedAt = Instant.now();
        return e;
    }

    void applyQuantity(int quantity) { this.quantity = quantity; this.updatedAt = Instant.now(); }

    CartItem toDomain() {
        return CartItem.of(id, userId, skuId, productId, itemName, optionSummary, unitPrice, quantity);
    }

    Long getId() { return id; }
}
```

`infrastructure/persistence/CartItemJpaRepository.java`:
```java
package com.example.order.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface CartItemJpaRepository extends JpaRepository<CartItemJpaEntity, Long> {
    List<CartItemJpaEntity> findByUserIdOrderByIdAsc(Long userId);
    Optional<CartItemJpaEntity> findByUserIdAndSkuId(Long userId, Long skuId);
    void deleteByUserIdAndSkuId(Long userId, Long skuId);
    void deleteByUserId(Long userId);
}
```

`infrastructure/persistence/CartRepositoryImpl.java`:
```java
package com.example.order.infrastructure.persistence;

import com.example.order.application.interfaces.CartRepository;
import com.example.order.domain.entity.CartItem;
import lombok.RequiredArgsConstructor;
import java.util.List;
import java.util.Optional;

@RequiredArgsConstructor
public class CartRepositoryImpl implements CartRepository {

    private final CartItemJpaRepository jpa;

    @Override
    public List<CartItem> findByUserId(long userId) {
        return jpa.findByUserIdOrderByIdAsc(userId).stream().map(CartItemJpaEntity::toDomain).toList();
    }

    @Override
    public Optional<CartItem> findByUserIdAndSkuId(long userId, long skuId) {
        return jpa.findByUserIdAndSkuId(userId, skuId).map(CartItemJpaEntity::toDomain);
    }

    @Override
    public CartItem save(CartItem item) {
        CartItemJpaEntity e;
        if (item.getId() == 0) {
            e = CartItemJpaEntity.forInsert(item);       // 신규 INSERT
        } else {
            e = jpa.findById(item.getId()).orElseThrow(); // 기존 로드 후 수량만 갱신(created_at 보존)
            e.applyQuantity(item.getQuantity());
        }
        return jpa.save(e).toDomain();
    }

    @Override
    public void deleteByUserIdAndSkuId(long userId, long skuId) { jpa.deleteByUserIdAndSkuId(userId, skuId); }

    @Override
    public void deleteByUserId(long userId) { jpa.deleteByUserId(userId); }
}
```

- [ ] **Step 5: PersistenceConfig에 @Bean 배선**

`infrastructure/config/PersistenceConfig.java` — import `CartRepository`, `CartItemJpaRepository`, `CartRepositoryImpl` 추가 후 빈 추가:
```java
    @Bean
    public com.example.order.application.interfaces.CartRepository cartRepository(
        com.example.order.infrastructure.persistence.CartItemJpaRepository jpa) {
        return new com.example.order.infrastructure.persistence.CartRepositoryImpl(jpa);
    }
```
(기존 import 스타일에 맞춰 상단 import로 정리해도 됨.)

- [ ] **Step 6: 레포 통합 테스트 (Testcontainers)**

`CartRepositoryImplTest.java` — 기존 order-service 통합 테스트(예: `OrderRepositoryImplTest`/`AbstractRepositoryTest`가 있으면 그 베이스) 스타일을 따라 작성. 커버:
- `save`(신규) 후 `findByUserId`로 1건 조회, 필드 일치.
- 같은 (user, sku) 다른 quantity로 `save`(id 지정) → 수량 갱신, 행 1개 유지, created_at 보존(변경 전후 동일).
- `findByUserIdAndSkuId` present/absent.
- `deleteByUserIdAndSkuId` 후 미조회, `deleteByUserId`로 전체 삭제.
- user 격리: userA 저장분이 userB `findByUserId`에 안 나옴.

(order-service에 통합 테스트 베이스가 없으면 `@DataJpaTest` + Testcontainers MySQL 또는 기존 `*RepositoryImplTest` 패턴을 그대로 미러. 실제 파일을 읽어 베이스 클래스/애노테이션을 맞출 것.)

- [ ] **Step 7: 컴파일 + 테스트**

Run: `./gradlew :order-service:test --tests "*CartRepositoryImplTest"`
Expected: PASS (Testcontainers 부팅으로 수 분 — 포그라운드 대기).

- [ ] **Step 8: 커밋**
```bash
git add order-service/src/main/resources/db/migration/V5__create_cart.sql \
        order-service/src/main/java/com/example/order/domain/entity/CartItem.java \
        order-service/src/main/java/com/example/order/application/interfaces/CartRepository.java \
        order-service/src/main/java/com/example/order/infrastructure/persistence/CartItemJpaEntity.java \
        order-service/src/main/java/com/example/order/infrastructure/persistence/CartItemJpaRepository.java \
        order-service/src/main/java/com/example/order/infrastructure/persistence/CartRepositoryImpl.java \
        order-service/src/main/java/com/example/order/infrastructure/config/PersistenceConfig.java \
        order-service/src/test/java/com/example/order/infrastructure/persistence/CartRepositoryImplTest.java
git commit -m "feat(order): 장바구니 영속 계층 — cart_item(V5) + CartRepository"
```

---

## Task 2: 백엔드 — cart 서비스·API (application + presentation)

**Files:**
- Create: `application/usecase/CartUseCase.java`
- Create: `application/service/CartService.java`
- Modify: `common/exception/ErrorCode.java`
- Create: `common/exception/application/CartItemNotFoundException.java`
- Create: `presentation/controller/CartController.java`
- Create: `presentation/dto/AddCartItemRequest.java`, `UpdateQuantityRequest.java`, `CartResponse.java`
- Test: `application/service/CartServiceTest.java`, `presentation/controller/CartControllerTest.java`

**Interfaces:**
- Consumes: `CartRepository`(Task 1).
- Produces:
  - `CartUseCase`: `List<CartItem> getCart(long userId)`; `CartItem addItem(long userId, AddCommand)`; `CartItem updateQuantity(long userId, long skuId, int quantity)`; `void removeItem(long userId, long skuId)`; `void clear(long userId)`.
  - API `/v1/cart` (아래).

- [ ] **Step 1: ErrorCode + 예외**

`ErrorCode.java`에 추가(enum 상수):
```java
    CART_ITEM_NOT_FOUND("CART_ITEM_NOT_FOUND", 404, "장바구니 항목을 찾을 수 없습니다."),
```
`domain/exception/CartItemNotFoundException.java` — BusinessException 하위는 `domain/exception/`에 위치(기존 `VerifyOrderItemNotFoundException`·`OrderOwnershipMismatchException` 패턴. 주의: `application/exception/OrderItemNotFoundException`은 BusinessException 아닌 Kafka 경로용이라 재사용 금지):
```java
package com.example.order.domain.exception;

import com.example.order.common.exception.BusinessException;
import com.example.order.common.exception.ErrorCode;

public class CartItemNotFoundException extends BusinessException {
    public CartItemNotFoundException() { super(ErrorCode.CART_ITEM_NOT_FOUND); }
}
```

- [ ] **Step 2: UseCase + Service 실패 테스트**

`CartServiceTest.java`(Mockito, `CartRepository` mock) 커버:
- `addItem` 신규(없을 때) → `save(create(...))` 호출, 반환 수량 = 요청 수량.
- `addItem` 병합(기존 qty=2, 신규 qty=3) → 기존 changeQuantity(5) 후 save, 반환 수량 5.
- `updateQuantity` 존재 → save, 반환 수량 = 설정값.
- `updateQuantity` 없음 → `CartItemNotFoundException`.
- `removeItem` → `deleteByUserIdAndSkuId(userId, skuId)`.
- `clear` → `deleteByUserId(userId)`.
- `getCart` → `findByUserId`.

```java
// 예시(핵심 2개):
@Test void addItem_merges_quantity_when_exists() {
    when(repo.findByUserIdAndSkuId(7L, 42L))
        .thenReturn(Optional.of(CartItem.of(1L,7L,42L,1L,"티","블랙/M",29000L,2)));
    when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));
    CartItem r = service.addItem(7L, new CartUseCase.AddCommand(42L,1L,"티","블랙/M",29000L,3));
    assertThat(r.getQuantity()).isEqualTo(5);
}
@Test void updateQuantity_absent_throws() {
    when(repo.findByUserIdAndSkuId(7L, 99L)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.updateQuantity(7L,99L,4))
        .isInstanceOf(CartItemNotFoundException.class);
}
```

- [ ] **Step 3: 실패 확인**
Run: `./gradlew :order-service:test --tests "*CartServiceTest"` → FAIL(미구현).

- [ ] **Step 4: UseCase + Service 구현**

`application/usecase/CartUseCase.java`:
```java
package com.example.order.application.usecase;

import com.example.order.domain.entity.CartItem;
import java.util.List;

public interface CartUseCase {
    record AddCommand(long skuId, long productId, String itemName, String optionSummary,
                      long unitPrice, int quantity) {}

    List<CartItem> getCart(long userId);
    CartItem addItem(long userId, AddCommand cmd);
    CartItem updateQuantity(long userId, long skuId, int quantity);
    void removeItem(long userId, long skuId);
    void clear(long userId);
}
```

`application/service/CartService.java`:
```java
package com.example.order.application.service;

import com.example.order.application.interfaces.CartRepository;
import com.example.order.application.usecase.CartUseCase;
import com.example.order.domain.exception.CartItemNotFoundException;
import com.example.order.domain.entity.CartItem;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class CartService implements CartUseCase {

    private final CartRepository cartRepository;

    @Override
    @Transactional(readOnly = true)
    public List<CartItem> getCart(long userId) {
        return cartRepository.findByUserId(userId);
    }

    @Override
    @Transactional
    public CartItem addItem(long userId, AddCommand c) {
        return cartRepository.findByUserIdAndSkuId(userId, c.skuId())
            .map(existing -> { existing.changeQuantity(existing.getQuantity() + c.quantity()); return cartRepository.save(existing); })
            .orElseGet(() -> cartRepository.save(CartItem.create(
                userId, c.skuId(), c.productId(), c.itemName(), c.optionSummary(), c.unitPrice(), c.quantity())));
    }

    @Override
    @Transactional
    public CartItem updateQuantity(long userId, long skuId, int quantity) {
        CartItem item = cartRepository.findByUserIdAndSkuId(userId, skuId)
            .orElseThrow(CartItemNotFoundException::new);
        item.changeQuantity(quantity);
        return cartRepository.save(item);
    }

    @Override
    @Transactional
    public void removeItem(long userId, long skuId) { cartRepository.deleteByUserIdAndSkuId(userId, skuId); }

    @Override
    @Transactional
    public void clear(long userId) { cartRepository.deleteByUserId(userId); }
}
```

- [ ] **Step 5: 서비스 테스트 통과**
Run: `./gradlew :order-service:test --tests "*CartServiceTest"` → PASS.

- [ ] **Step 6: DTO + 컨트롤러 + 컨트롤러 테스트**

DTO:
```java
// presentation/dto/AddCartItemRequest.java
package com.example.order.presentation.dto;
import jakarta.validation.constraints.*;
public record AddCartItemRequest(
    @NotNull Long skuId, @NotNull Long productId, @NotBlank String itemName,
    String optionSummary, @NotNull @PositiveOrZero Long unitPrice, @Positive int quantity) {}
```
```java
// presentation/dto/UpdateQuantityRequest.java
package com.example.order.presentation.dto;
import jakarta.validation.constraints.Positive;
public record UpdateQuantityRequest(@Positive int quantity) {}
```
```java
// presentation/dto/CartResponse.java
package com.example.order.presentation.dto;
import com.example.order.domain.entity.CartItem;
import java.util.List;
public record CartResponse(List<Item> items) {
    public record Item(long skuId, long productId, String itemName, String optionSummary,
                       long unitPrice, int quantity) {}
    public static CartResponse from(List<CartItem> items) {
        return new CartResponse(items.stream()
            .map(c -> new Item(c.getSkuId(), c.getProductId(), c.getItemName(),
                c.getOptionSummary(), c.getUnitPrice(), c.getQuantity()))
            .toList());
    }
}
```

`presentation/controller/CartController.java`:
```java
package com.example.order.presentation.controller;

import com.example.order.application.usecase.CartUseCase;
import com.example.order.presentation.dto.AddCartItemRequest;
import com.example.order.presentation.dto.CartResponse;
import com.example.order.presentation.dto.UpdateQuantityRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/v1/cart")
@RequiredArgsConstructor
public class CartController {

    private final CartUseCase cartUseCase;

    @GetMapping
    public CartResponse get(@RequestHeader("X-User-Id") long userId) {
        return CartResponse.from(cartUseCase.getCart(userId));
    }

    @PostMapping("/items")
    public ResponseEntity<CartResponse> add(@RequestHeader("X-User-Id") long userId,
                                            @RequestBody @Valid AddCartItemRequest req) {
        cartUseCase.addItem(userId, new CartUseCase.AddCommand(
            req.skuId(), req.productId(), req.itemName(), req.optionSummary(), req.unitPrice(), req.quantity()));
        return ResponseEntity.status(HttpStatus.CREATED).body(CartResponse.from(cartUseCase.getCart(userId)));
    }

    @PatchMapping("/items/{skuId}")
    public CartResponse update(@RequestHeader("X-User-Id") long userId, @PathVariable long skuId,
                               @RequestBody @Valid UpdateQuantityRequest req) {
        cartUseCase.updateQuantity(userId, skuId, req.quantity());
        return CartResponse.from(cartUseCase.getCart(userId));
    }

    @DeleteMapping("/items/{skuId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void remove(@RequestHeader("X-User-Id") long userId, @PathVariable long skuId) {
        cartUseCase.removeItem(userId, skuId);
    }

    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void clear(@RequestHeader("X-User-Id") long userId) { cartUseCase.clear(userId); }
}
```

`CartControllerTest.java`(MockMvc standaloneSetup + GlobalExceptionHandler, `CartUseCase` mock — 기존 order 컨트롤러 테스트 스타일):
- `GET /v1/cart` + X-User-Id → 200 + `$.items` 매핑.
- `POST /v1/cart/items` valid → 201; `quantity<=0`/필수 누락 → 400.
- `PATCH /v1/cart/items/{skuId}` → 200; 없는 sku(usecase가 `CartItemNotFoundException`) → 404.
- `DELETE /v1/cart/items/{skuId}` → 204; `DELETE /v1/cart` → 204.
- 각 호출이 usecase에 X-User-Id를 그대로 전달하는지 verify.

- [ ] **Step 7: 전체 order-service 테스트(회귀)**
Run: `./gradlew :order-service:test`
Expected: PASS (기존 + cart 전부). Testcontainers로 수 분 — 포그라운드 대기.

- [ ] **Step 8: 커밋**
```bash
git add order-service/src/main/java/com/example/order/application/usecase/CartUseCase.java \
        order-service/src/main/java/com/example/order/application/service/CartService.java \
        order-service/src/main/java/com/example/order/common/exception/ErrorCode.java \
        order-service/src/main/java/com/example/order/domain/exception/CartItemNotFoundException.java \
        order-service/src/main/java/com/example/order/presentation/controller/CartController.java \
        order-service/src/main/java/com/example/order/presentation/dto/AddCartItemRequest.java \
        order-service/src/main/java/com/example/order/presentation/dto/UpdateQuantityRequest.java \
        order-service/src/main/java/com/example/order/presentation/dto/CartResponse.java \
        order-service/src/test/java/com/example/order/application/service/CartServiceTest.java \
        order-service/src/test/java/com/example/order/presentation/controller/CartControllerTest.java
git commit -m "feat(order): 장바구니 API — GET/POST/PATCH/DELETE /v1/cart (X-User-Id 스코프)"
```

---

## Task 3: 게이트웨이 — /v1/cart 인증 라우트

**Files:**
- Modify: `api-gateway/src/main/java/com/example/gateway/config/RouteConfig.java`
- Test: `api-gateway/src/test/java/com/example/gateway/integration/GatewayRoutingIT.java`

**Interfaces:**
- Produces: `/v1/cart/**` → order downstream, JwtTrustHeaderFilter 부착(토큰 필요, X-User-* strip/주입).

- [ ] **Step 1: cartRoute 빈 추가**

`RouteConfig.java`에 빈 추가(order 라우트와 동일 패턴, order-uri 사용):
```java
    /** 장바구니(cart) — 인증 라우트. order-service downstream(cart는 order-service에 위치). */
    @Bean
    RouterFunction<ServerResponse> cartRoute(
            JwtTrustHeaderFilter jwt,
            @Value("${gateway.downstream.order-uri}") String orderUri) {
        return route("cart")
                .route(path("/v1/cart/**"), http())
                .before(uri(orderUri))
                .filter(jwt)
                .build();
    }
```
클래스 javadoc 라우트 목록에 cart 한 줄 추가.

- [ ] **Step 2: GatewayRoutingIT에 테스트 2개**

`createOrder_noToken_...`/`createOrder_validJwt_...` 패턴을 미러:
```java
@Test
void cart_noToken_returns401_downstreamNotCalled() throws Exception {
    HttpResponse<String> res = http.send(
            HttpRequest.newBuilder(URI.create(gateway("/v1/cart"))).GET().build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(res.statusCode()).isEqualTo(401);
    assertThat(res.body()).contains("TOKEN_MISSING");
    orderDownstream.verify(0, anyRequestedFor(anyUrl()));
}

@Test
void cart_validJwt_routesToOrderDownstream_withTrustHeaderInjected() throws Exception {
    orderDownstream.stubFor(get(urlPathEqualTo("/v1/cart"))
            .willReturn(aResponse().withStatus(200).withBody("{\"items\":[]}")));
    String token = accessToken(42L, "USER", null);
    HttpResponse<String> res = http.send(
            HttpRequest.newBuilder(URI.create(gateway("/v1/cart")))
                    .header("Authorization", "Bearer " + token)
                    .header(JwtTrustHeaderFilter.H_USER_ID, "9999") // 위조 → strip
                    .GET().build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(res.statusCode()).isEqualTo(200);
    orderDownstream.verify(getRequestedFor(urlPathEqualTo("/v1/cart"))
            .withHeader(JwtTrustHeaderFilter.H_USER_ID, equalTo("42")));
    paymentDownstream.verify(0, anyRequestedFor(anyUrl()));
    userDownstream.verify(0, anyRequestedFor(anyUrl()));
}
```
(static import `get`, `getRequestedFor`는 이미 파일에 존재 — 확인. GET은 SAFE라 CSRF 불요.)

- [ ] **Step 3: 게이트웨이 테스트**
Run: `./gradlew :api-gateway:test` → PASS(신규 2개 + 기존).

- [ ] **Step 4: 커밋**
```bash
git add api-gateway/src/main/java/com/example/gateway/config/RouteConfig.java \
        api-gateway/src/test/java/com/example/gateway/integration/GatewayRoutingIT.java
git commit -m "feat(gateway): /v1/cart 인증 라우트(order downstream)"
```

---

## Task 4: 프론트 — api.js 장바구니 함수

**Files:**
- Modify: `frontend/src/api.js`

**Interfaces:**
- Produces: `api.getCart()`, `api.addCartItem(b)`, `api.updateCartItem(skuId, quantity)`, `api.removeCartItem(skuId)`, `api.clearCart()`.

- [ ] **Step 1: api 객체에 추가**
```js
  getCart:        ()             => req('/v1/cart'),
  addCartItem:    (b)            => req('/v1/cart/items', { method: 'POST', body: b, csrf: true }),
  updateCartItem: (skuId, quantity) => req(`/v1/cart/items/${skuId}`, { method: 'PATCH', body: { quantity }, csrf: true }),
  removeCartItem: (skuId)        => req(`/v1/cart/items/${skuId}`, { method: 'DELETE', csrf: true }),
  clearCart:      ()             => req('/v1/cart', { method: 'DELETE', csrf: true }),
```

- [ ] **Step 2: 스모크**
Run: `curl -s -o /dev/null -w "%{http_code}\n" http://localhost:8000/v1/cart`
Expected: 401/403(미인증). (게이트웨이 down이면 000 — 허용, 서버 기동 금지.)

- [ ] **Step 3: 커밋**
```bash
git add frontend/src/api.js
git commit -m "feat(cart-fe): api.js 장바구니 함수"
```

---

## Task 5: 프론트 — Cart 화면 + 담기 + 네비바 + App 배선

**Files:**
- Create: `frontend/src/components/Cart.jsx`
- Modify: `frontend/src/components/ProductDetail.jsx`
- Modify: `frontend/src/components/NavBar.jsx`
- Modify: `frontend/src/App.jsx`
- Modify: `frontend/src/App.css`

**Interfaces:**
- Consumes: `api.getCart/addCartItem/updateCartItem/removeCartItem/clearCart`, P1 `Checkout`/`OrderSuccess`.
- Produces: 장바구니 담기(ProductDetail) · 네비바 개수 · Cart 뷰 · 결제 성공 시 비움.

- [ ] **Step 1: Cart.jsx**
```jsx
export default function Cart({ items, onQty, onRemove, onOrder, onBack }) {
  const total = items.reduce((s, it) => s + it.unitPrice * it.quantity, 0)
  const lines = items.map(it => ({
    skuId: it.skuId, productId: it.productId, itemName: it.itemName,
    optionSummary: it.optionSummary, unitPrice: it.unitPrice, quantity: it.quantity,
  }))
  return (
    <main className="cart">
      <button onClick={onBack}>뒤로</button>
      <h1>장바구니</h1>
      {items.length === 0 ? <p>장바구니가 비어 있습니다.</p> : (
        <>
          <table className="cart-table">
            <thead><tr><th>상품</th><th>옵션</th><th>단가</th><th>수량</th><th>합계</th><th></th></tr></thead>
            <tbody>
              {items.map(it => (
                <tr key={it.skuId}>
                  <td>{it.itemName}</td><td>{it.optionSummary}</td>
                  <td>₩{it.unitPrice.toLocaleString()}</td>
                  <td>
                    <input className="qty-input" type="number" min="1" step="1" value={it.quantity}
                           onChange={e => onQty(it.skuId, Math.max(1, Math.floor(Number(e.target.value) || 1)))} />
                  </td>
                  <td>₩{(it.unitPrice * it.quantity).toLocaleString()}</td>
                  <td><button onClick={() => onRemove(it.skuId)}>삭제</button></td>
                </tr>
              ))}
            </tbody>
          </table>
          <p className="cart-total">합계 <strong>₩{total.toLocaleString()}</strong></p>
          <button className="pay-btn" onClick={() => onOrder(lines)}>주문하기</button>
        </>
      )}
    </main>
  )
}
```

- [ ] **Step 2: ProductDetail.jsx — "장바구니 담기" 추가**

기존 "구매하기" 버튼 옆에 추가. `onAddToCart` prop을 받아 `lines`로 호출:
```jsx
// props에 onAddToCart 추가: ProductDetail({ id, me, onBack, onBuy, onAddToCart })
// 구매하기 버튼 아래(또는 옆)에:
<button className="cart-btn" disabled={lines.length === 0} onClick={() => onAddToCart(lines)}>장바구니 담기</button>
```
(나머지 로직/수량 상태는 P1 그대로. `lines` 계산도 P1 그대로 재사용.)

- [ ] **Step 3: NavBar.jsx — 장바구니 개수**

`me`가 있으면 `장바구니({cartCount})` 버튼 표시(`onCart`). props에 `cartCount`, `onCart` 추가:
```jsx
{me && <button onClick={onCart}>장바구니({cartCount})</button>}
```
(로그인/로그아웃/브랜드 등 기존 구조 유지 — `me ? (이름+로그아웃+장바구니) : 로그인`.)

- [ ] **Step 4: App.jsx — cart 상태·뷰 배선**

```jsx
// 추가 import: import Cart from './components/Cart'
// 상태: const [cart, setCart] = useState([])
// me 세팅 후/로그인 시 장바구니 로드:
const loadCart = () => api.getCart().then(r => setCart(r.items)).catch(() => setCart([]))
useEffect(() => { if (me) loadCart() }, [me])

// 담기 핸들러:
async function handleAddToCart(lines) {
  if (!me) { setAuthOpen(true); return }
  for (const l of lines) {
    await api.addCartItem({ skuId: l.skuId, productId: l.productId, itemName: l.itemName,
      optionSummary: l.optionSummary, unitPrice: l.unitPrice, quantity: l.quantity })
  }
  await loadCart()
  setView({ name: 'cart' })
}

// Cart 수량/삭제:
const onQty = async (skuId, q) => { await api.updateCartItem(skuId, q); loadCart() }
const onRemove = async (skuId) => { await api.removeCartItem(skuId); loadCart() }

// NavBar: cartCount={cart.reduce((s,i)=>s+i.quantity,0)} onCart={() => setView({name:'cart'})}
// ProductDetail: onAddToCart={handleAddToCart}
// Cart 뷰:
{view.name === 'cart' && (
  <Cart items={cart} onQty={onQty} onRemove={onRemove}
        onOrder={(lines) => setView({ name: 'checkout', lines })}
        onBack={() => setView({ name: 'home' })} />
)}

// Checkout onPaid: 결제 성공 시 장바구니 비우기(장바구니 경유였을 수 있으므로 항상 clear + reload)
onPaid={async (payment) => { try { await api.clearCart() } catch {} ; setCart([]); setView({ name: 'success', payment }) }}
```
(P1의 home/detail/checkout/success 뷰 유지 + cart 뷰 추가. 바로구매(onBuy)도 그대로.)

- [ ] **Step 5: App.css — cart 스타일**
```css
.cart { max-width: 680px; margin: 0 auto; padding: 16px; }
.cart-table { width: 100%; border-collapse: collapse; margin: 12px 0; }
.cart-table th, .cart-table td { border-bottom: 1px solid #eee; padding: 8px; text-align: left; }
.cart-total { text-align: right; font-size: 18px; margin: 12px 0; }
.cart-btn { margin-top: 12px; margin-left: 8px; padding: 10px 16px; background: #0ea5e9; color: #fff; border: none; border-radius: 6px; cursor: pointer; }
.cart-btn:disabled { opacity: 0.5; cursor: default; }
```

- [ ] **Step 6: dev 로드 검증**
Run(스택 실행 중 가정):
```bash
cd frontend
node -e "import('@playwright/test').then(async({chromium})=>{const b=await chromium.launch();const p=await b.newPage();await p.goto('http://localhost:5173');await p.waitForSelector('.grid .card');await p.click('.grid .card');await p.waitForSelector('.cart-btn');console.log('장바구니 담기 버튼 렌더 OK');await b.close()})"
```
Expected: `.cart-btn` 렌더(모듈 해석 에러 없음).

- [ ] **Step 7: 커밋**
```bash
git add frontend/src/components/Cart.jsx frontend/src/components/ProductDetail.jsx \
        frontend/src/components/NavBar.jsx frontend/src/App.jsx frontend/src/App.css
git commit -m "feat(cart-fe): 장바구니 화면 + 담기 + 네비바 개수 + App 배선"
```

---

## Task 6: E2E — 장바구니 저니

**Files:**
- Create: `frontend/e2e/cart.spec.js`

**Prerequisites:** 인프라 + user·product·order(cart, V5 적용)·payment·gateway + `npm run dev`. order-service는 **이 브랜치(cart) 코드**로 기동(V5 마이그레이션 적용). product-service는 skuId 노출(P1) 코드.

- [ ] **Step 1: E2E 스펙**
```js
import { test, expect } from '@playwright/test'
const BASE = 'http://localhost:5173'
const GW = 'http://localhost:8000'
const USER = { email: `cart${Date.now()}@example.com`, password: 'password123', name: '카트유저', phone: '010-4444-5555' }

test.beforeAll(async ({ request }) => { await request.post(`${GW}/v1/auth/signup`, { data: USER }).catch(() => {}) })

test('장바구니: 담기 → 장바구니 → 수량수정 → 주문하기 → 결제 → 완료 + 비움', async ({ page }) => {
  await page.goto(BASE)
  await page.click('.navbar-right button')                 // 로그인 모달
  await page.fill('input[placeholder="email"]', USER.email)
  await page.fill('input[placeholder="password"]', USER.password)
  await page.click('.modal button[type="submit"]')
  await expect(page.locator('.navbar-right span')).toBeVisible()

  // 상품 상세 → 수량 1 → 장바구니 담기
  await page.click('.grid .card')
  await page.waitForSelector('.cart-btn')
  await page.locator('.qty-input').first().fill('1')
  await page.click('.cart-btn')

  // 장바구니 뷰(담기 후 자동 이동) → 개수/합계 확인
  await expect(page.locator('.cart h1')).toHaveText('장바구니')
  await expect(page.locator('.cart-table tbody tr')).toHaveCount(1)

  // 수량 2로 수정
  await page.locator('.cart-table .qty-input').first().fill('2')
  await page.waitForTimeout(300)

  // 주문하기 → 결제 → 완료
  await page.click('.pay-btn')                              // 주문하기(Cart) → checkout
  await expect(page.locator('.checkout h1')).toHaveText('주문하기')
  await page.click('.pay-btn')                              // 결제하기(Checkout)
  await expect(page.locator('.order-success h1')).toContainText('결제 완료')

  // 장바구니 비워졌는지 (네비바 개수 0 / 재진입 빈 목록)
  await page.click('text=쇼핑 계속하기')
  await expect(page.locator('.navbar-right')).toContainText('장바구니(0)')
})
```

- [ ] **Step 2: 실행**
Run: `cd frontend && npx playwright test e2e/cart.spec.js` → 1 passed.
(실패 시 테스트 셀렉터/타이밍만 조정; src/·백엔드 우회 금지. 실제 앱 버그면 보고.)

- [ ] **Step 3: 커밋**
```bash
git add frontend/e2e/cart.spec.js
git commit -m "test(cart-fe): 장바구니 저니 E2E"
```

---

## Self-Review 결과

- **Spec 커버리지:** V5+cart 영속(Task1)·cart API+병합/설정(Task2)·게이트웨이 라우트(Task3)·api.js(Task4)·Cart/담기/네비바/App(Task5)·E2E(Task6) — 전 항목 매핑. Checkout/OrderSuccess는 P1 재사용(무변경). 결제 성공 시 clearCart(App onPaid).
- **타입 일관성:** cart `lines`/CartResponse.Item 필드(`skuId, productId, itemName, optionSummary, unitPrice, quantity`)가 백엔드 DTO ↔ api ↔ Cart ↔ Checkout 동일. addItem 병합(기존+신규)=Service; updateQuantity=설정. skuId를 경로변수로 사용(백엔드 UK user_id+sku_id와 정합).
- **논-골 준수:** 주문내역·조회 API 미포함. 취소 코어·order 생성/검증·order/order_item 무변경. cart 독립. merchantId=1 유지.
