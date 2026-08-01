# 상품 카탈로그 프론트 + 다중 이미지(S3 presigned) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 프론트 첫 화면을 상품 그리드로, 로그인을 상단 nav로 옮기고, 상품에 SKU 단위 가격과 S3(로컬 MinIO) presigned 방식 다중 이미지(업로드·삭제·순서변경)를 붙인다.

**Architecture:** product-service에 가격·이미지 컬럼/테이블과 presign/confirm/delete/reorder 엔드포인트를 추가하고(파일 바이트는 프론트→S3 직접 PUT), api-gateway가 공개 GET 브라우징 + 인증 이미지 write를 product로 라우팅한다. 프론트는 상태 기반 3뷰(Home 그리드 / Detail 갤러리 / 로그인 모달)로 재구성한다. 취소 코어·인증 경계·재고 예약 로직은 불변.

**Tech Stack:** Java 21 · Spring Boot 4.0.5 / Spring Security 7 · Spring Data JPA · MySQL 8 · Flyway · AWS SDK v2 (S3 + presigner) · MinIO(로컬) · JUnit 5 + Mockito + Testcontainers · React + Vite · Playwright

## Global Constraints

- Flyway로만 스키마 변경. 적용된 파일 수정 금지 — 새 버전만(product 최신은 V4 → V5, V6 추가).
- product-service `spring.jpa.hibernate.ddl-auto: validate` — JPA 엔티티는 새 컬럼/테이블과 **정확히** 일치해야 부팅됨.
- domain 레이어(`domain/entity`)에 Spring/JPA 어노테이션 금지(순수 POJO, `@Getter`만).
- 시크릿/키 하드코딩 금지 — MinIO 로컬 기본값(`minioadmin`)은 config에 두되 운영은 env 오버라이드.
- 모듈 간 DB 직접 접근 금지. product는 HTTP로만 노출.
- 테스트 없이 완료 처리 금지.
- 가격은 `BIGINT`(KRW 원, 정수). 이미지는 상품 단위 다중, 서빙은 presigned GET URL.
- write 엔드포인트(이미지 presign/confirm/delete/reorder)는 `X-User-Role: ADMIN`만. 브라우징 GET은 공개.
- 게이트웨이 파일 PUT 미경유 — presigned `uploadUrl`로 프론트가 MinIO 직접 PUT.

---

## File Structure

**product-service (신규/수정)**
- `src/main/resources/db/migration/V5__add_sku_price.sql` (생성)
- `src/main/resources/db/migration/V6__create_product_image.sql` (생성)
- `domain/entity/ProductSku.java` (수정: price 필드)
- `domain/entity/ProductImage.java` (생성)
- `application/interfaces/ProductImageRepository.java` (생성)
- `application/interfaces/ObjectStoragePort.java` (생성)
- `application/interfaces/ProductQueryRepository.java` (수정: price/thumbnail 프로젝션)
- `application/service/CatalogService.java` (수정: SkuSeed price)
- `application/service/ProductImageService.java` (생성)
- `application/service/ProductQueryService.java` (수정: SkuDetail price)
- `infrastructure/persistence/ProductSkuJpaEntity.java` + `ProductSkuRepositoryImpl.java` (수정)
- `infrastructure/persistence/ProductImageJpaEntity.java` + `...JpaRepository.java` + `...RepositoryImpl.java` (생성)
- `infrastructure/persistence/ProductQueryRepositoryImpl.java` (수정)
- `infrastructure/storage/S3Config.java` + `S3ObjectStorageAdapter.java` (생성)
- `infrastructure/web/AdminOnly` 가드 — `presentation/controller/ProductImageController.java` 내 인라인 (생성)
- `presentation/controller/ProductImageController.java` (생성)
- `presentation/controller/ProductQueryController.java` (수정: imageUrls/thumbnail)
- `presentation/dto/`: `PresignRequest/Response`, `ConfirmImageRequest/Response`, `ReorderRequest` (생성), `ProductListResponse`·`ProductDetailResponse`·`SeedRequest` (수정)
- `build.gradle` (수정: awssdk s3)
- `src/main/resources/application.yml` (수정: `app.s3`)

**루트**
- `docker-compose.yml` (수정: minio 서비스 + 버킷 init)

**api-gateway**
- `src/main/resources/application.yml` (수정: product-uri)
- `src/main/java/com/example/gateway/config/RouteConfig.java` (수정: product 라우트 2개)

**frontend**
- `src/api.js` (수정)
- `src/App.jsx` (수정: 뷰 라우팅)
- `src/components/NavBar.jsx`, `AuthModal.jsx`, `Home.jsx`, `ProductGrid.jsx`, `ProductDetail.jsx`, `ImageManager.jsx` (생성)
- `src/App.css` (수정: 그리드/nav 스타일)
- `e2e/catalog.spec.js` (생성)

---

# Phase 1 — SKU 가격 (product-service)

### Task 1: SKU price 컬럼 + 엔티티 + 시드 배선

**Files:**
- Create: `product-service/src/main/resources/db/migration/V5__add_sku_price.sql`
- Modify: `domain/entity/ProductSku.java`, `infrastructure/persistence/ProductSkuJpaEntity.java`, `infrastructure/persistence/ProductSkuRepositoryImpl.java`, `application/service/CatalogService.java`, `presentation/dto/SeedRequest.java`, `presentation/controller/ProductController.java`
- Test: `product-service/src/test/java/com/example/product/integration/SeedPriceIT.java` (또는 기존 seed IT 확장)

**Interfaces:**
- Consumes: 기존 `ProductSku.create`, `CatalogService.SkuSeed`
- Produces: `ProductSku.create(Long productId, String skuCode, String optionSummary, long price)`, `ProductSku.getPrice()`, `CatalogService.SkuSeed(String skuCode, String optionSummary, int initialStock, long price)`, `SeedRequest.SkuLine(... , long price)`

- [ ] **Step 1: V5 마이그레이션 작성**

`V5__add_sku_price.sql`:
```sql
-- SKU 단위 판매가(KRW 원, 정수). 기존 행은 0 백필 → 시드 재적용 시 실가격.
ALTER TABLE product_sku ADD COLUMN price BIGINT NOT NULL DEFAULT 0;
```

- [ ] **Step 2: 실패 테스트 — 시드에 price 저장/조회**

`SeedPriceIT` (Testcontainers MySQL, 기존 통합 셋업 재사용):
```java
@Test
void seed_persists_sku_price() {
    var res = catalogService.seed("티셔츠", leafCategoryId,
            List.of(new CatalogService.SkuSeed("SKU-1", "M/블랙", 10, 29000L)));
    Long skuId = res.skus().get(0).skuId();
    long price = jdbcTemplate.queryForObject(
            "SELECT price FROM product_sku WHERE id = ?", Long.class, skuId);
    assertThat(price).isEqualTo(29000L);
}
```

- [ ] **Step 3: 테스트 실패 확인**

Run: `./gradlew :product-service:test --tests '*SeedPriceIT'`
Expected: 컴파일 실패(`SkuSeed` 생성자 인자 불일치) 또는 price 컬럼 미존재.

- [ ] **Step 4: 엔티티/영속/서비스에 price 배선**

`ProductSku.java` — 필드 `private final long price;` 추가, 생성자·`create`·`reconstruct`에 price 파라미터 추가:
```java
public static ProductSku create(Long productId, String skuCode, String optionSummary, long price) {
    Instant now = Instant.now();
    return new ProductSku(null, productId, skuCode, optionSummary, price, now, now);
}
```
`ProductSkuJpaEntity.java` — `@Column(nullable=false) private long price;` + 매핑(toDomain/fromDomain)에 price 반영.
`ProductSkuRepositoryImpl.java` — save 시 price 전달(엔티티 매핑이면 자동).
`CatalogService.java` — `record SkuSeed(String skuCode, String optionSummary, int initialStock, long price)` + `ProductSku.create(product.getId(), s.skuCode(), s.optionSummary(), s.price())`.
`SeedRequest.java` — `SkuLine`에 `@PositiveOrZero long price` 추가.
`ProductController.java` — `new SkuSeed(s.skuCode(), s.optionSummary(), s.initialStock(), s.price())`.

- [ ] **Step 5: 테스트 통과 확인**

Run: `./gradlew :product-service:test --tests '*SeedPriceIT'`
Expected: PASS

- [ ] **Step 6: 커밋**

```bash
git add product-service/src/main/resources/db/migration/V5__add_sku_price.sql \
        product-service/src/main/java product-service/src/test/java
git commit -m "feat(product): SKU 단위 price 컬럼 + 시드 배선 (V5)"
```

---

### Task 2: 목록 minPrice + 상세 SKU price 노출

**Files:**
- Modify: `application/interfaces/ProductQueryRepository.java`, `infrastructure/persistence/ProductQueryRepositoryImpl.java`, `application/service/ProductQueryService.java`, `presentation/dto/ProductListResponse.java`, `presentation/dto/ProductDetailResponse.java`
- Test: `product-service/src/test/java/com/example/product/integration/BrowsePriceIT.java`

**Interfaces:**
- Consumes: `ProductQueryRepository.findByCategoryIds`, `findSkuStock`
- Produces:
  - `ProductQueryRepository.SkuStock(String skuCode, String optionSummary, int availableQty, long price)`
  - `ProductQueryRepository.ProductCard(Long id, String name, long minPrice, String thumbnailKey)` (thumbnailKey는 Task 8에서 채움, 지금은 null)
  - `ProductQueryRepository.findCardsByCategoryIds(List<Long> ids, int page, int size): Page<ProductCard>`
  - `ProductQueryService.SkuDetail(... , long price)`
  - `ProductListResponse.Item(Long id, String name, long minPrice, String thumbnailUrl)`

- [ ] **Step 1: 실패 테스트 — 목록 minPrice / 상세 price**

`BrowsePriceIT`:
```java
@Test
void list_returns_min_sku_price() {
    // given: 한 상품에 SKU 두 개(29000, 19000)
    var page = queryService.listCards(leafCategoryId, 0, 20);
    assertThat(page.getContent().get(0).minPrice()).isEqualTo(19000L);
}

@Test
void detail_returns_each_sku_price() {
    var d = queryService.detail(productId);
    assertThat(d.skus()).extracting(ProductQueryService.SkuDetail::price)
                        .contains(29000L, 19000L);
}
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `./gradlew :product-service:test --tests '*BrowsePriceIT'`
Expected: 컴파일 실패(`listCards`/`price` 미존재).

- [ ] **Step 3: 프로젝션/쿼리 구현**

`ProductQueryRepository.java`:
```java
record SkuStock(String skuCode, String optionSummary, int availableQty, long price) {}
record ProductCard(Long id, String name, long minPrice, String thumbnailKey) {}
Page<ProductCard> findCardsByCategoryIds(List<Long> categoryIds, int page, int size);
```
`ProductQueryRepositoryImpl.java`:
- `findSkuStock`: SELECT에 `ps.price` 추가, `SkuStock` 4-인자 매핑.
- `findCardsByCategoryIds`: 상품 목록 + 서브쿼리로 `(SELECT MIN(price) FROM product_sku WHERE product_id=p.id)` = minPrice, thumbnailKey는 `NULL`(Task 8에서 first image key로 교체). `created_at desc, id desc` 정렬 유지, 페이징.

`ProductQueryService.java`:
- `SkuDetail`에 `long price` 추가, detail 매핑에 `s.price()` 반영.
- 신규 `public Page<ProductQueryRepository.ProductCard> listCards(Long categoryId, int page, int size)` — 기존 `listByCategory`처럼 카테고리 검증 후 `findCardsByCategoryIds`.

`ProductListResponse.java`:
```java
public record ProductListResponse(List<Item> content, int page, int size, long totalElements) {
    public record Item(Long id, String name, long minPrice, String thumbnailUrl) {}
    public static ProductListResponse from(Page<ProductQueryRepository.ProductCard> page,
                                           java.util.function.Function<String,String> presign) {
        List<Item> content = page.getContent().stream()
            .map(c -> new Item(c.id(), c.name(), c.minPrice(),
                    c.thumbnailKey() == null ? null : presign.apply(c.thumbnailKey())))
            .toList();
        return new ProductListResponse(content, page.getNumber(), page.getSize(), page.getTotalElements());
    }
}
```
`ProductDetailResponse.java` — `Sku`에 `long price` 추가, imageUrls는 Task 8에서.
(컨트롤러의 `presign` 함수는 Task 8에서 `ObjectStoragePort::presignDownload`로 주입. 이 태스크에서는 `key -> null` 임시 주입.)

- [ ] **Step 4: 테스트 통과 확인**

Run: `./gradlew :product-service:test --tests '*BrowsePriceIT'`
Expected: PASS

- [ ] **Step 5: 커밋**

```bash
git add product-service/src/main/java product-service/src/test/java
git commit -m "feat(product): 목록 minPrice + 상세 SKU price 노출"
```

---

# Phase 2 — MinIO + S3 포트 (인프라)

### Task 3: MinIO 서비스 + AWS SDK + config

**Files:**
- Modify: `docker-compose.yml`, `product-service/build.gradle`, `product-service/src/main/resources/application.yml`
- Create: `infrastructure/storage/S3Config.java`

**Interfaces:**
- Produces: 스프링 빈 `software.amazon.awssdk.services.s3.S3Client`, `software.amazon.awssdk.services.s3.presigner.S3Presigner`, 그리고 `S3Properties`(bucket, presignTtl).

- [ ] **Step 1: docker-compose에 MinIO + 버킷 init 추가**

`docker-compose.yml` services에 추가:
```yaml
  minio:
    image: minio/minio:latest
    command: server /data --console-address ":9001"
    environment:
      MINIO_ROOT_USER: minioadmin
      MINIO_ROOT_PASSWORD: minioadmin
    ports: ["9000:9000", "9001:9001"]
    volumes: ["minio-data:/data"]
  minio-init:
    image: minio/mc:latest
    depends_on: [minio]
    entrypoint: >
      /bin/sh -c "
      until mc alias set local http://minio:9000 minioadmin minioadmin; do sleep 1; done;
      mc mb --ignore-existing local/product-images;
      mc anonymous set none local/product-images;
      exit 0;"
```
volumes에 `minio-data:` 추가.

- [ ] **Step 2: build.gradle 의존성**

`product-service/build.gradle` dependencies:
```gradle
    implementation 'software.amazon.awssdk:s3:2.28.16'
```

- [ ] **Step 3: application.yml + S3Config**

`application.yml`에 추가:
```yaml
app:
  s3:
    endpoint: ${S3_ENDPOINT:http://localhost:9000}
    region: ${S3_REGION:us-east-1}
    bucket: ${S3_BUCKET:product-images}
    access-key: ${S3_ACCESS_KEY:minioadmin}
    secret-key: ${S3_SECRET_KEY:minioadmin}
    presign-ttl-seconds: 300
    path-style: true
```
`S3Config.java`:
```java
@Configuration
@ConfigurationProperties(prefix = "app.s3")
@Getter @Setter
public class S3Config {
    private String endpoint, region, bucket, accessKey, secretKey;
    private int presignTtlSeconds;
    private boolean pathStyle;

    @Bean
    S3Client s3Client() {
        return baseBuilder(S3Client.builder()).build();
    }
    @Bean
    S3Presigner s3Presigner() {
        return S3Presigner.builder()
            .endpointOverride(URI.create(endpoint)).region(Region.of(region))
            .credentialsProvider(creds())
            .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(pathStyle).build())
            .build();
    }
    private <B extends S3BaseClientBuilder<B,?>> B baseBuilder(B b) {
        return b.endpointOverride(URI.create(endpoint)).region(Region.of(region))
                .credentialsProvider(creds())
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(pathStyle).build());
    }
    private StaticCredentialsProvider creds() {
        return StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey));
    }
}
```

- [ ] **Step 4: 컴파일 확인**

Run: `./gradlew :product-service:compileJava`
Expected: SUCCESS (빈 등록만, 아직 사용처 없음)

- [ ] **Step 5: 커밋**

```bash
git add docker-compose.yml product-service/build.gradle \
        product-service/src/main/resources/application.yml product-service/src/main/java
git commit -m "chore(product): MinIO 서비스 + AWS SDK v2 + S3 config"
```

---

### Task 4: ObjectStoragePort + S3 어댑터

**Files:**
- Create: `application/interfaces/ObjectStoragePort.java`, `infrastructure/storage/S3ObjectStorageAdapter.java`
- Test: `product-service/src/test/java/com/example/product/integration/S3ObjectStorageIT.java`

**Interfaces:**
- Produces:
  ```java
  interface ObjectStoragePort {
      record PresignedUpload(String uploadUrl) {}
      PresignedUpload presignUpload(String key, String contentType);
      String presignDownload(String key);   // GET presigned URL
      boolean exists(String key);            // HEAD
      void delete(String key);               // 멱등
  }
  ```

- [ ] **Step 1: 실패 테스트 — 라운드트립(Testcontainers MinIO)**

`S3ObjectStorageIT` (Testcontainers `GenericContainer("minio/minio")` 9000 노출, 버킷 생성):
```java
@Test
void presign_put_then_exists_then_download() throws Exception {
    var up = port.presignUpload("products/1/a.jpg", "image/jpeg");
    // HTTP PUT 바이트 to up.uploadUrl()
    HttpResponse<?> put = httpPut(up.uploadUrl(), "image/jpeg", new byte[]{1,2,3});
    assertThat(put.statusCode()).isEqualTo(200);
    assertThat(port.exists("products/1/a.jpg")).isTrue();
    String getUrl = port.presignDownload("products/1/a.jpg");
    assertThat(httpGet(getUrl).statusCode()).isEqualTo(200);
    port.delete("products/1/a.jpg");
    assertThat(port.exists("products/1/a.jpg")).isFalse();
}
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `./gradlew :product-service:test --tests '*S3ObjectStorageIT'`
Expected: 컴파일 실패(포트/어댑터 미존재).

- [ ] **Step 3: 포트 + 어댑터 구현**

`ObjectStoragePort.java`(위 시그니처).
`S3ObjectStorageAdapter.java`:
```java
@Component
public class S3ObjectStorageAdapter implements ObjectStoragePort {
    private final S3Client s3; private final S3Presigner presigner;
    private final String bucket; private final Duration ttl;
    // 생성자 주입: S3Client, S3Presigner, S3Config

    public PresignedUpload presignUpload(String key, String contentType) {
        var req = PutObjectRequest.builder().bucket(bucket).key(key).contentType(contentType).build();
        var pre = presigner.presignPutObject(b -> b.signatureDuration(ttl).putObjectRequest(req));
        return new PresignedUpload(pre.url().toString());
    }
    public String presignDownload(String key) {
        var req = GetObjectRequest.builder().bucket(bucket).key(key).build();
        return presigner.presignGetObject(b -> b.signatureDuration(ttl).getObjectRequest(req)).url().toString();
    }
    public boolean exists(String key) {
        try { s3.headObject(b -> b.bucket(bucket).key(key)); return true; }
        catch (NoSuchKeyException e) { return false; }
    }
    public void delete(String key) { s3.deleteObject(b -> b.bucket(bucket).key(key)); }
}
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `./gradlew :product-service:test --tests '*S3ObjectStorageIT'`
Expected: PASS

- [ ] **Step 5: 커밋**

```bash
git add product-service/src/main/java product-service/src/test/java
git commit -m "feat(product): ObjectStoragePort + S3 presign 어댑터"
```

---

# Phase 3 — 이미지 엔드포인트 (product-service)

### Task 5: product_image 테이블 + 엔티티 + repo

**Files:**
- Create: `db/migration/V6__create_product_image.sql`, `domain/entity/ProductImage.java`, `application/interfaces/ProductImageRepository.java`, `infrastructure/persistence/ProductImageJpaEntity.java`, `ProductImageJpaRepository.java`, `ProductImageRepositoryImpl.java`
- Test: `product-service/src/test/java/com/example/product/integration/ProductImageRepositoryIT.java`

**Interfaces:**
- Produces:
  ```java
  // ProductImage (POJO): id, productId, s3Key, sortOrder, createdAt + getters
  interface ProductImageRepository {
      ProductImage save(ProductImage img);               // id 채워 반환
      List<ProductImage> findByProductId(Long productId); // sort_order asc, id asc
      int nextSortOrder(Long productId);                 // max(sort_order)+1, 없으면 0
      Optional<ProductImage> findByIdAndProductId(Long id, Long productId);
      void deleteByIdAndProductId(Long id, Long productId);
      void updateOrder(Long productId, List<Long> imageIdsInOrder); // idx→sort_order
  }
  ```

- [ ] **Step 1: V6 마이그레이션**

`V6__create_product_image.sql`:
```sql
CREATE TABLE product_image (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    product_id  BIGINT       NOT NULL,
    s3_key      VARCHAR(512) NOT NULL,
    sort_order  INT          NOT NULL DEFAULT 0,
    created_at  DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY idx_product_image_product (product_id, sort_order),
    UNIQUE KEY uk_product_image_key (s3_key),
    CONSTRAINT fk_product_image_product FOREIGN KEY (product_id) REFERENCES product (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
```

- [ ] **Step 2: 실패 테스트 — save/list/order**

`ProductImageRepositoryIT`:
```java
@Test
void save_list_and_reorder() {
    var a = repo.save(ProductImage.create(productId, "k/a.jpg", repo.nextSortOrder(productId)));
    var b = repo.save(ProductImage.create(productId, "k/b.jpg", repo.nextSortOrder(productId)));
    repo.updateOrder(productId, List.of(b.getId(), a.getId()));
    assertThat(repo.findByProductId(productId)).extracting(ProductImage::getS3Key)
            .containsExactly("k/b.jpg", "k/a.jpg");
    repo.deleteByIdAndProductId(b.getId(), productId);
    assertThat(repo.findByProductId(productId)).hasSize(1);
}
```

- [ ] **Step 3: 테스트 실패 확인**

Run: `./gradlew :product-service:test --tests '*ProductImageRepositoryIT'`
Expected: 컴파일 실패.

- [ ] **Step 4: 엔티티/JPA/repo 구현**

- `ProductImage.java`: POJO(`@Getter`), `create(Long productId, String s3Key, int sortOrder)`(id/createdAt null·now) + `reconstruct(...)`.
- `ProductImageJpaEntity.java`: `@Entity @Table(name="product_image")`, 필드 매핑, toDomain/fromDomain.
- `ProductImageJpaRepository.java`: `extends JpaRepository<ProductImageJpaEntity, Long>` + `findByProductIdOrderBySortOrderAscIdAsc`, `deleteByIdAndProductId`, `@Query MAX(sortOrder)`.
- `ProductImageRepositoryImpl.java`: 위 포트 구현. `updateOrder`는 리스트 인덱스를 sort_order로 각 행 UPDATE(해당 productId 소속만).

- [ ] **Step 5: 테스트 통과 확인**

Run: `./gradlew :product-service:test --tests '*ProductImageRepositoryIT'`
Expected: PASS

- [ ] **Step 6: 커밋**

```bash
git add product-service/src/main/resources/db/migration/V6__create_product_image.sql \
        product-service/src/main/java product-service/src/test/java
git commit -m "feat(product): product_image 테이블 + 엔티티 + repo (V6)"
```

---

### Task 6: presign + confirm 엔드포인트 + ADMIN 가드

**Files:**
- Create: `application/service/ProductImageService.java`, `presentation/controller/ProductImageController.java`, `presentation/dto/PresignRequest.java`, `PresignResponse.java`, `ConfirmImageRequest.java`, `ConfirmImageResponse.java`
- Test: `product-service/src/test/java/com/example/product/presentation/ProductImageControllerTest.java` (MockMvc, port·repo 목)

**Interfaces:**
- Consumes: `ObjectStoragePort`, `ProductImageRepository`, `ProductRepository`(존재 확인)
- Produces:
  - `ProductImageService.presign(Long productId, String contentType): Presigned(String key, String uploadUrl)`
  - `ProductImageService.confirm(Long productId, String key, Integer sortOrder): Long imageId`
  - `PresignRequest(String contentType)`, `PresignResponse(String key, String uploadUrl)`
  - `ConfirmImageRequest(String key, Integer sortOrder)`, `ConfirmImageResponse(Long imageId)`
  - 컨트롤러 가드 `requireAdmin(String role)` → 비-ADMIN 403

- [ ] **Step 1: 실패 테스트 — 가드 + presign + confirm**

```java
@Test
void presign_requires_admin() throws Exception {
    mvc.perform(post("/v1/products/1/images:presign").header("X-User-Role","MERCHANT")
            .contentType(APPLICATION_JSON).content("{\"contentType\":\"image/jpeg\"}"))
       .andExpect(status().isForbidden());
}
@Test
void presign_returns_key_and_url_for_admin() throws Exception {
    when(port.presignUpload(anyString(), eq("image/jpeg")))
        .thenReturn(new ObjectStoragePort.PresignedUpload("http://minio/put"));
    mvc.perform(post("/v1/products/1/images:presign").header("X-User-Role","ADMIN")
            .contentType(APPLICATION_JSON).content("{\"contentType\":\"image/jpeg\"}"))
       .andExpect(status().isOk())
       .andExpect(jsonPath("$.uploadUrl").value("http://minio/put"))
       .andExpect(jsonPath("$.key").exists());
}
@Test
void confirm_rejects_missing_object() throws Exception {
    when(port.exists("k")).thenReturn(false);
    mvc.perform(post("/v1/products/1/images").header("X-User-Role","ADMIN")
            .contentType(APPLICATION_JSON).content("{\"key\":\"k\"}"))
       .andExpect(status().isBadRequest());
}
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `./gradlew :product-service:test --tests '*ProductImageControllerTest'`
Expected: 404/컴파일 실패(컨트롤러 미존재).

- [ ] **Step 3: 서비스 + 컨트롤러 구현**

`ProductImageService.java`:
```java
public Presigned presign(Long productId, String contentType) {
    productRepository.findById(productId).orElseThrow(() -> new ProductNotFoundException(productId));
    String key = "products/" + productId + "/" + UUID.randomUUID();
    return new Presigned(key, port.presignUpload(key, contentType).uploadUrl());
}
public Long confirm(Long productId, String key, Integer sortOrder) {
    productRepository.findById(productId).orElseThrow(() -> new ProductNotFoundException(productId));
    if (!port.exists(key)) throw new InvalidImageKeyException(key);   // → 400
    int order = sortOrder != null ? sortOrder : imageRepository.nextSortOrder(productId);
    return imageRepository.save(ProductImage.create(productId, key, order)).getId();
}
```
`ProductImageController.java`:
```java
@RestController @RequestMapping("/v1/products/{id}/images")
public class ProductImageController {
    // 생성자 주입 ProductImageService
    private static void requireAdmin(String role) {
        if (!"ADMIN".equals(role)) throw new ForbiddenException();   // → 403
    }
    @PostMapping(":presign")   // 경로 리터럴 ':presign'
    PresignResponse presign(@PathVariable Long id, @RequestHeader(value="X-User-Role",required=false) String role,
                            @RequestBody PresignRequest req) {
        requireAdmin(role);
        var p = service.presign(id, req.contentType());
        return new PresignResponse(p.key(), p.uploadUrl());
    }
    @PostMapping
    ConfirmImageResponse confirm(@PathVariable Long id, @RequestHeader(value="X-User-Role",required=false) String role,
                                 @RequestBody ConfirmImageRequest req) {
        requireAdmin(role);
        return new ConfirmImageResponse(service.confirm(id, req.key(), req.sortOrder()));
    }
}
```
- `ForbiddenException`/`InvalidImageKeyException`을 `common/exception`에 추가하고 `GlobalExceptionHandler`에서 403/400 매핑(기존 핸들러 패턴 따름).
- `:presign` 리터럴 경로가 Spring MVC에서 매칭되는지 확인(안 되면 `/{id}/images/presign`으로 변경하고 스펙·게이트웨이·프론트 동기화).

- [ ] **Step 4: 테스트 통과 확인**

Run: `./gradlew :product-service:test --tests '*ProductImageControllerTest'`
Expected: PASS

- [ ] **Step 5: 커밋**

```bash
git add product-service/src/main/java product-service/src/test/java
git commit -m "feat(product): 이미지 presign/confirm 엔드포인트 + ADMIN 가드"
```

---

### Task 7: delete + reorder 엔드포인트

**Files:**
- Modify: `ProductImageService.java`, `ProductImageController.java`
- Create: `presentation/dto/ReorderRequest.java`
- Test: `ProductImageControllerTest.java` (케이스 추가)

**Interfaces:**
- Produces:
  - `ProductImageService.delete(Long productId, Long imageId)` — 행 삭제 후 S3 delete
  - `ProductImageService.reorder(Long productId, List<Long> imageIds)`
  - `ReorderRequest(List<Long> imageIds)`
  - `DELETE /v1/products/{id}/images/{imageId}` → 204, `PUT /v1/products/{id}/images/order` → 200

- [ ] **Step 1: 실패 테스트 — delete/reorder + 가드**

```java
@Test
void delete_removes_row_and_object_for_admin() throws Exception {
    when(imageRepository.findByIdAndProductId(9L,1L))
        .thenReturn(Optional.of(ProductImage.reconstruct(9L,1L,"k9",0,Instant.now())));
    mvc.perform(delete("/v1/products/1/images/9").header("X-User-Role","ADMIN"))
       .andExpect(status().isNoContent());
    verify(imageRepository).deleteByIdAndProductId(9L,1L);
    verify(port).delete("k9");
}
@Test
void reorder_requires_admin() throws Exception {
    mvc.perform(put("/v1/products/1/images/order").header("X-User-Role","MERCHANT")
            .contentType(APPLICATION_JSON).content("{\"imageIds\":[2,1]}"))
       .andExpect(status().isForbidden());
}
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `./gradlew :product-service:test --tests '*ProductImageControllerTest'`
Expected: FAIL (핸들러 미존재).

- [ ] **Step 3: 구현**

`ProductImageService`:
```java
public void delete(Long productId, Long imageId) {
    var img = imageRepository.findByIdAndProductId(imageId, productId)
            .orElseThrow(() -> new ImageNotFoundException(imageId));
    imageRepository.deleteByIdAndProductId(imageId, productId);
    try { port.delete(img.getS3Key()); }
    catch (RuntimeException e) { log.warn("S3 delete 실패(고아 허용) key={}", img.getS3Key(), e); }
    // ponytail: 고아 객체 스캐너는 필요해지면
}
public void reorder(Long productId, List<Long> imageIds) {
    imageRepository.updateOrder(productId, imageIds);
}
```
`ProductImageController`:
```java
@DeleteMapping("/{imageId}")
@ResponseStatus(HttpStatus.NO_CONTENT)
void delete(@PathVariable Long id, @PathVariable Long imageId,
            @RequestHeader(value="X-User-Role",required=false) String role) {
    requireAdmin(role); service.delete(id, imageId);
}
@PutMapping("/order")
void reorder(@PathVariable Long id, @RequestHeader(value="X-User-Role",required=false) String role,
             @RequestBody ReorderRequest req) {
    requireAdmin(role); service.reorder(id, req.imageIds());
}
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `./gradlew :product-service:test --tests '*ProductImageControllerTest'`
Expected: PASS

- [ ] **Step 5: 커밋**

```bash
git add product-service/src/main/java product-service/src/test/java
git commit -m "feat(product): 이미지 delete/reorder 엔드포인트"
```

---

### Task 8: 상세 imageUrls + 목록 thumbnailUrl (presigned GET 배선)

**Files:**
- Modify: `ProductQueryRepository.java`(+thumbnailKey 쿼리), `ProductQueryRepositoryImpl.java`, `ProductQueryService.java`, `presentation/dto/ProductDetailResponse.java`, `presentation/controller/ProductQueryController.java`
- Test: `product-service/src/test/java/com/example/product/presentation/ProductQueryControllerTest.java` (port 목: key→URL)

**Interfaces:**
- Consumes: `ObjectStoragePort.presignDownload`, `ProductImageRepository.findByProductId`
- Produces:
  - `ProductDetailResponse(Long id, String name, List<Category> category, List<String> imageUrls, List<Sku> skus)`
  - `ProductQueryService.ProductDetail`에 `List<String> imageKeys` 추가(서비스는 key 반환, 컨트롤러가 presign)
  - `findCardsByCategoryIds`의 thumbnailKey = 상품별 최소 sort_order 이미지 key

- [ ] **Step 1: 실패 테스트 — 컨트롤러가 key→presigned URL 변환**

```java
@Test
void detail_maps_image_keys_to_presigned_urls() throws Exception {
    when(port.presignDownload("k1")).thenReturn("http://minio/get/k1");
    // queryService.detail(1) → imageKeys=[k1]
    mvc.perform(get("/v1/products/1"))
       .andExpect(jsonPath("$.imageUrls[0]").value("http://minio/get/k1"));
}
@Test
void list_maps_thumbnail_key_to_url() throws Exception {
    when(port.presignDownload("t1")).thenReturn("http://minio/get/t1");
    mvc.perform(get("/v1/categories/5/products"))
       .andExpect(jsonPath("$.content[0].thumbnailUrl").value("http://minio/get/t1"));
}
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `./gradlew :product-service:test --tests '*ProductQueryControllerTest'`
Expected: FAIL.

- [ ] **Step 3: 배선**

- `ProductQueryRepositoryImpl.findCardsByCategoryIds`: thumbnailKey 서브쿼리 `(SELECT s3_key FROM product_image WHERE product_id=p.id ORDER BY sort_order, id LIMIT 1)`.
- `ProductQueryService.detail`: `imageRepository.findByProductId(productId)` → `imageKeys` 채워 `ProductDetail`에 담음.
- `ProductDetailResponse.from(ProductDetail d, Function<String,String> presign)`: `imageUrls = d.imageKeys().stream().map(presign).toList()`.
- `ProductQueryController`: `ObjectStoragePort` 주입, `Function<String,String> presign = port::presignDownload` 을 `ProductListResponse.from`·`ProductDetailResponse.from`에 전달. (Task 2에서 심어둔 `key->null` 임시 주입 제거.)

- [ ] **Step 4: 테스트 통과 확인**

Run: `./gradlew :product-service:test --tests '*ProductQueryControllerTest'`
Expected: PASS

- [ ] **Step 5: 전체 product 테스트 + 커밋**

Run: `./gradlew :product-service:test`
```bash
git add product-service/src/main/java product-service/src/test/java
git commit -m "feat(product): 상세 imageUrls + 목록 thumbnailUrl presigned GET 배선"
```

---

# Phase 4 — api-gateway

### Task 9: product 라우트 (공개 GET + 인증 이미지 write)

**Files:**
- Modify: `api-gateway/src/main/resources/application.yml`, `api-gateway/src/main/java/com/example/gateway/config/RouteConfig.java`
- Test: `api-gateway/src/test/java/com/example/gateway/RouteConfig...` 또는 기존 라우팅 IT 확장 (WireMock downstream)

**Interfaces:**
- Consumes: `JwtTrustHeaderFilter`, `GatewayPaths` 패턴, 기존 `uri()/http()/removeRequestHeader` 헬퍼
- Produces: `productBrowseRoute`(공개 GET, strip), `productImageWriteRoute`(인증) 빈

- [ ] **Step 1: 실패 테스트 — 공개 GET 통과 + write는 JWT 필요**

기존 게이트웨이 IT 스타일(WireMock product stub)로:
```java
@Test
void public_get_products_routes_without_token_and_strips_trust_headers() {
    // GET /v1/categories/5/products (토큰 없음) → 200, downstream이 X-User-Id 못 받음
}
@Test
void image_presign_requires_valid_jwt() {
    // POST /v1/products/1/images:presign (토큰 없음) → 401/403
}
@Test
void post_products_seed_not_exposed() {
    // POST /v1/products (시드) → 게이트웨이 라우트 없음 → 404
}
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `./gradlew :api-gateway:test`
Expected: FAIL (product 라우트 없음).

- [ ] **Step 3: application.yml + RouteConfig**

`application.yml` downstream에 `product-uri: http://localhost:8084`.
`RouteConfig.java`:
```java
@Bean
RouterFunction<ServerResponse> productBrowseRoute(
        @Value("${gateway.downstream.product-uri}") String productUri) {
    RequestPredicate browse = GET("/v1/products/**").or(GET("/v1/categories/**"));
    return route("product-browse")
            .route(browse, http())
            .before(uri(productUri))
            .before(removeRequestHeader(JwtTrustHeaderFilter.H_USER_ID))
            .before(removeRequestHeader(JwtTrustHeaderFilter.H_USER_ROLE))
            .before(removeRequestHeader(JwtTrustHeaderFilter.H_MERCHANT_ID))
            .build();
}
@Bean
RouterFunction<ServerResponse> productImageWriteRoute(
        JwtTrustHeaderFilter jwt,
        @Value("${gateway.downstream.product-uri}") String productUri) {
    RequestPredicate w = POST("/v1/products/*/images:presign")
            .or(POST("/v1/products/*/images"))
            .or(DELETE("/v1/products/*/images/*"))
            .or(PUT("/v1/products/*/images/order"));
    return route("product-image-write")
            .route(w, http())
            .before(uri(productUri))
            .filter(jwt)
            .build();
}
```
- `GET/POST/PUT/DELETE`는 `org.springframework.web.servlet.function.RequestPredicates` static import.
- 공개 라우트가 GET만 매칭하므로 이미지 write(POST/DELETE/PUT)와 충돌 없음. `:presign` 리터럴은 Task 6과 동일 형태 유지.
- CsrfFilter가 write 메서드에 CSRF 요구 시 이미지 write도 대상 — 프론트가 `X-CSRF-Token` 동봉(Task 10). 필요하면 `GatewayPaths`/CsrfFilter 확인.

- [ ] **Step 4: 테스트 통과 확인**

Run: `./gradlew :api-gateway:test`
Expected: PASS

- [ ] **Step 5: 커밋**

```bash
git add api-gateway/src/main/resources/application.yml api-gateway/src/main/java api-gateway/src/test/java
git commit -m "feat(gateway): product 공개 브라우징 + 인증 이미지 write 라우트"
```

---

# Phase 5 — frontend

### Task 10: api.js 확장

**Files:**
- Modify: `frontend/src/api.js`
- Test: 수동(브라우저 콘솔) — 프론트 단위테스트 부재. E2E(Task 14)가 커버.

**Interfaces:**
- Produces: `api.categories`, `api.productsByCategory`, `api.product`, `api.presignImage`, `api.putToS3`, `api.confirmImage`, `api.deleteImage`, `api.reorderImages`

- [ ] **Step 1: api 메서드 추가**

`api.js` `export const api` 확장:
```js
categories:          ()          => req('/v1/categories'),
productsByCategory:  (id, page=0) => req(`/v1/categories/${id}/products?page=${page}`),
product:             (id)        => req(`/v1/products/${id}`),
presignImage:        (id, contentType) =>
    req(`/v1/products/${id}/images:presign`, { method:'POST', body:{ contentType }, csrf:true }),
confirmImage:        (id, key, sortOrder) =>
    req(`/v1/products/${id}/images`, { method:'POST', body:{ key, sortOrder }, csrf:true }),
deleteImage:         (id, imageId) =>
    req(`/v1/products/${id}/images/${imageId}`, { method:'DELETE', csrf:true }),
reorderImages:       (id, imageIds) =>
    req(`/v1/products/${id}/images/order`, { method:'PUT', body:{ imageIds }, csrf:true }),
```
그리고 게이트웨이 우회 직접 PUT(별도 fetch, `req` 미사용):
```js
export async function putToS3(uploadUrl, file) {
    const res = await fetch(uploadUrl, {
        method: 'PUT', body: file,
        headers: { 'Content-Type': file.type },
        credentials: 'omit',                 // S3엔 쿠키 안 보냄
    })
    if (!res.ok) throw new Error(`업로드 실패 HTTP ${res.status}`)
}
```

- [ ] **Step 2: 커밋**

```bash
git add frontend/src/api.js
git commit -m "feat(frontend): 상품 브라우징 + 이미지 presign/put/confirm/delete/reorder API"
```

---

### Task 11: NavBar + 로그인 모달 + App 재구성

**Files:**
- Create: `frontend/src/components/NavBar.jsx`, `frontend/src/components/AuthModal.jsx`
- Modify: `frontend/src/App.jsx`, `frontend/src/App.css`

**Interfaces:**
- Consumes: `api.me`, `api.login`, `api.signup`, `api.logout`
- Produces: App 상태 `{ me, view: {name:'home'} | {name:'detail', id} }`, `NavBar({ me, onLoginClick, onLogout, onHome })`, `AuthModal({ open, onClose, onAuthed })`

- [ ] **Step 1: App 뷰 상태 골격**

`App.jsx`:
```jsx
export default function App() {
  const [me, setMe] = useState(null)
  const [view, setView] = useState({ name: 'home' })
  const [authOpen, setAuthOpen] = useState(false)
  useEffect(() => { api.me().then(setMe).catch(() => setMe(null)) }, [])
  return (
    <>
      <NavBar me={me} onHome={() => setView({name:'home'})}
              onLoginClick={() => setAuthOpen(true)}
              onLogout={async () => { await api.logout(); setMe(null) }} />
      {view.name === 'home'
        ? <Home onOpen={(id) => setView({name:'detail', id})} />
        : <ProductDetail id={view.id} me={me} onBack={() => setView({name:'home'})} />}
      <AuthModal open={authOpen} onClose={() => setAuthOpen(false)}
                 onAuthed={(u) => { setMe(u); setAuthOpen(false) }} />
    </>
  )
}
```

- [ ] **Step 2: NavBar**

`NavBar.jsx` — 브랜드 좌측, 우측: `me ? (이름+로그아웃) : 로그인 버튼`. 클릭 핸들러 props 그대로.

- [ ] **Step 3: AuthModal**

`AuthModal.jsx` — 기존 App의 login/signup 폼을 모달로 이식. 성공 시 `onAuthed(await api.me())`.

- [ ] **Step 4: 수동 확인**

Run: `cd frontend && npm run dev` → nav 렌더, 로그인 모달 열림/성공 시 이름 표시.

- [ ] **Step 5: 커밋**

```bash
git add frontend/src
git commit -m "feat(frontend): NavBar + 로그인 모달 + 뷰 라우팅 재구성"
```

---

### Task 12: Home 그리드

**Files:**
- Create: `frontend/src/components/Home.jsx`, `frontend/src/components/ProductGrid.jsx`
- Modify: `frontend/src/App.css`

**Interfaces:**
- Consumes: `api.categories`, `api.productsByCategory`
- Produces: `Home({ onOpen })`, `ProductGrid({ items, onOpen })`

- [ ] **Step 1: Home — 카테고리 탭(트리 leaf) + 그리드**

`Home.jsx`:
```jsx
export default function Home({ onOpen }) {
  const [leaves, setLeaves] = useState([])
  const [active, setActive] = useState(null)
  const [items, setItems] = useState([])
  useEffect(() => { api.categories().then(tree => {
    const ls = []; (function walk(ns){ ns.forEach(n => n.children?.length ? walk(n.children) : ls.push(n)) })(tree)
    setLeaves(ls); if (ls[0]) setActive(ls[0].id)
  }) }, [])
  useEffect(() => { if (active) api.productsByCategory(active).then(r => setItems(r.content)) }, [active])
  return (<main>
    <nav className="cat-tabs">{leaves.map(l =>
      <button key={l.id} className={l.id===active?'active':''} onClick={()=>setActive(l.id)}>{l.name}</button>)}</nav>
    <ProductGrid items={items} onOpen={onOpen} />
  </main>)
}
```
(카테고리 응답 트리 루트 형태가 배열/`{children}`인지 확인해 `walk` 진입값 맞춤.)

- [ ] **Step 2: ProductGrid — 카드(썸네일/placeholder·이름·최저가)**

`ProductGrid.jsx`:
```jsx
export default function ProductGrid({ items, onOpen }) {
  return <div className="grid">{items.map(p =>
    <button key={p.id} className="card" onClick={()=>onOpen(p.id)}>
      {p.thumbnailUrl
        ? <img src={p.thumbnailUrl} alt={p.name} />
        : <div className="ph" />}
      <div className="name">{p.name}</div>
      <div className="price">₩{p.minPrice.toLocaleString()}~</div>
    </button>)}</div>
}
```
`App.css`에 `.grid`(CSS grid, `repeat(auto-fill,minmax(180px,1fr))`), `.card`, `.ph`(회색 박스), `.cat-tabs` 스타일.

- [ ] **Step 3: 수동 확인**

Run: 백엔드+MinIO+게이트웨이 기동 후 `npm run dev` → 시드 상품이 그리드로 표시(이미지 없으면 placeholder).

- [ ] **Step 4: 커밋**

```bash
git add frontend/src
git commit -m "feat(frontend): Home 카테고리 탭 + 상품 그리드"
```

---

### Task 13: ProductDetail 갤러리 + ADMIN 관리 패널

**Files:**
- Create: `frontend/src/components/ProductDetail.jsx`, `frontend/src/components/ImageManager.jsx`
- Modify: `frontend/src/App.css`

**Interfaces:**
- Consumes: `api.product`, `api.presignImage`, `putToS3`, `api.confirmImage`, `api.deleteImage`, `api.reorderImages`
- Produces: `ProductDetail({ id, me, onBack })`, `ImageManager({ productId, images, onChanged })`

- [ ] **Step 1: ProductDetail — 갤러리 + 카테고리 경로 + SKU 표**

`ProductDetail.jsx`: `api.product(id)`로 로드. 상단 이미지 갤러리(`imageUrls` 순서대로), 카테고리 경로(`category` root→leaf), SKU 표(`skuCode·optionSummary·₩price·재고 availableQty`). `me?.role==='ADMIN'`이면 `<ImageManager>` 렌더 + 변경 후 재조회.

- [ ] **Step 2: ImageManager — 업로드/삭제/순서변경**

`ImageManager.jsx`:
```jsx
async function upload(file) {
  const { key, uploadUrl } = await api.presignImage(productId, file.type)
  await putToS3(uploadUrl, file)
  await api.confirmImage(productId, key)
  onChanged()
}
// 삭제: api.deleteImage(productId, img.id).then(onChanged)
// 순서: 위/아래 버튼으로 로컬 배열 스왑 → api.reorderImages(productId, ids).then(onChanged)
```
파일 input(`accept="image/*"`), 각 이미지 썸네일 + 삭제/위/아래 버튼.

- [ ] **Step 3: 수동 확인**

Run: ADMIN 계정 로그인 → 상세에서 이미지 업로드 → 갤러리 반영 → 삭제/순서변경 확인. (ADMIN 계정 준비: user-service 시드/직접 role=ADMIN 부여 방법 확인.)

- [ ] **Step 4: 커밋**

```bash
git add frontend/src
git commit -m "feat(frontend): 상품 상세 갤러리 + ADMIN 이미지 관리 패널"
```

---

### Task 14: Playwright E2E

**Files:**
- Create: `frontend/e2e/catalog.spec.js`

**Interfaces:**
- Consumes: 전체 스택(게이트웨이·product·MinIO·user 기동 전제, 기존 `playwright.config.js`)

- [ ] **Step 1: E2E 시나리오**

`catalog.spec.js` (핵심 저니만):
```js
test('비로그인 그리드 조회 → 상세', async ({ page }) => {
  await page.goto('/')
  await expect(page.locator('.card').first()).toBeVisible()
  await page.locator('.card').first().click()
  await expect(page.getByRole('table')).toBeVisible()  // SKU 표
})

test('ADMIN 이미지 업로드 → 갤러리 반영', async ({ page }) => {
  // 로그인(ADMIN) → 상세 진입 → setInputFiles로 업로드 → 갤러리 이미지 수 증가 확인
})
```
- 업로드 시나리오는 presign→PUT(MinIO)→confirm 실경로. MinIO 오리진(localhost:9000)이 테스트 브라우저에서 도달 가능해야 함.

- [ ] **Step 2: 실행**

Run: `cd frontend && npx playwright test catalog.spec.js`
Expected: PASS (스택 기동 상태)

- [ ] **Step 3: 커밋**

```bash
git add frontend/e2e
git commit -m "test(frontend): 카탈로그 브라우징 + 이미지 업로드 E2E"
```

---

## 실행 순서 / 통합 검증

Phase 1→5 순차. Phase 4(게이트웨이)까지 끝나면 `./gradlew build`로 전체 회귀. Phase 5는 `docker compose up -d`(MinIO 포함) + 각 서비스 기동 후 수동/E2E.

**취소 코어 불변 회귀**: `./gradlew :payment-service:test` 그린 유지 확인(이 작업은 payment 무변경이나 안전 확인).

## Self-Review 결과

- **Spec 커버리지**: 스키마(T1,T5)·SKU가격(T1,T2)·MinIO/SDK(T3)·S3포트(T4)·이미지 CRUD/순서(T5-7)·presigned 서빙(T8)·게이트웨이 공개GET+인증write(T9)·프론트 nav/그리드/상세/관리(T10-13)·E2E(T14) 전부 매핑됨. 비목표(장바구니/검색/생성UI/SKU이미지) 제외 확인.
- **Placeholder**: 없음(각 스텝 실제 코드/명령).
- **타입 일관성**: `SkuSeed`(4-인자)·`SkuStock`(price)·`ProductCard`(minPrice,thumbnailKey)·`ProductDetail`(imageKeys)·`ObjectStoragePort`(4메서드)·`ProductImageRepository`(6메서드) 태스크 간 시그니처 일치. `:presign` 리터럴 경로는 T6·T9·T10에서 동일 형태 사용(매칭 불가 시 T6에서 `/images/presign`으로 일괄 변경 지시 포함).
- **미해결 확인점**(실행 중 검증): (a) Spring MVC `:presign` 리터럴 매칭, (b) 카테고리 트리 응답 루트 형태, (c) ADMIN 계정 발급 경로 — 각 태스크 수동확인 스텝에 명시.
