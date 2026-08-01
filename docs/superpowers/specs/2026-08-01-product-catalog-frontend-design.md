# 상품 카탈로그 프론트엔드 + 다중 이미지(S3 presigned) 설계

- 날짜: 2026-08-01
- 상태: 승인됨(설계) → 실행계획 대기
- 범위: 4개 영역 — (1) 신규 스토리지 인프라(MinIO) · (2) product-service 백엔드(가격·다중이미지) · (3) api-gateway 라우트 · (4) frontend(nav·그리드·상세·관리 UI)

## 배경 / 문제

현재 frontend는 로그인/회원가입만 되는 49줄짜리 데모(`frontend/src/App.jsx`)다. 첫 화면이 상품 그리드여야 하고 로그인은 상단 nav로 빠져야 한다. 그런데 두 가지가 막혀 있다:

1. **게이트웨이가 product-service(:8084)를 라우팅하지 않는다.** `RouteConfig`에 payment/user-auth/order만 있고 product 라우트·product-uri가 없다. 프론트는 게이트웨이(:8000)만 부르므로 상품을 못 가져온다.
2. **상품 데이터가 이름뿐이다.** `product` 테이블은 `id, name, category_id, created_at`뿐이고 목록 DTO(`ProductListResponse.Item`)는 `{id, name}`만 준다. 가격·이미지가 없어 그리드 카드가 이름표만 된다.

## 목표

- 첫 화면 = 카테고리 탭 + 상품 그리드(썸네일·이름·최저가), 상단 nav에 로그인/로그아웃.
- 상품 상세 = 이미지 갤러리(다중) + 카테고리 경로 + SKU별(옵션·가격·재고).
- 이미지는 **S3 presigned URL 직접 업로드** 방식(프론트가 S3로 바이트 직접 PUT, 백엔드는 키만 발급/기록). 상품당 다중 이미지, 삭제·순서변경 지원.
- 로컬은 MinIO로 자기완결, 운영은 엔드포인트 config만 바꿔 실 S3.

## 비목표 (이번 스코프 밖)

- 장바구니·주문 생성 연결(order-service) — 브라우징까지만.
- 검색·필터·정렬(카테고리 탭 외).
- 상품/SKU/카테고리 생성 UI — 시드는 기존 `POST /v1/products`(:8084 직접)로. 게이트웨이 미노출 유지.
- SKU별 이미지 — 이미지는 **상품 단위**.
- 이미지 리사이즈/썸네일 생성 파이프라인 — 원본 그대로, 썸네일은 "첫 이미지"를 의미.

## 핵심 결정 (확정)

| 항목 | 결정 | 근거 |
|------|------|------|
| 가격 위치 | **SKU 단위** (`product_sku.price`) | 사용자 선택. 목록은 상품 SKU 최저가 표시. |
| 가격 타입 | `BIGINT` (KRW 원, 정수) | 원화는 소수 없음. |
| 이미지 개수 | **상품당 N장** (`product_image` 테이블) | 사용자 선택. |
| 업로드 방식 | **S3 presigned PUT** (프론트 직접) | 사용자 선택. 파일 바이트를 앱 서버에 안 태움. |
| 서빙 | **비공개 버킷 + presigned GET URL** | presigned 일관성. `// ponytail: 요청마다 presign; 공개 CDN이면 정적 URL로` |
| 로컬 스토리지 | **MinIO** (docker-compose) | 자기완결·AWS 크레덴셜 불필요·presigned 그대로 동작. |
| 이미지 관리 | **업로드 + 삭제 + 순서변경** | 사용자 선택. |
| 권한 | write=ADMIN, 브라우징=공개 | 상품 관리는 관리자, 조회는 로그인 전에도. |
| 프론트 범위 | nav + 그리드 + 상세 + 관리 UI | 사용자 선택. |

---

## 아키텍처

### 영역 1 — 신규 인프라 (MinIO)

- `docker-compose.yml`에 `minio` 서비스 추가(콘솔 포함). 기동 시 버킷 `product-images` 생성(별도 init 컨테이너 또는 앱 부팅 시 `createBucket` 멱등).
- **CORS**: 브라우저가 presigned PUT을 직접 쏘므로 버킷 CORS에 프론트 오리진(`http://localhost:5173`) `PUT`/`GET` 허용. MinIO는 기본 전 오리진 허용에 가까우나 명시 설정.
- product-service 의존성: `software.amazon.awssdk:s3`(+ 필요시 `aws-crt` 불필요, 기본 apache client). 버전은 BOM 없이 단일 좌표로 고정.
- 설정(`application.yml`, product-service):
  ```yaml
  app.s3:
    endpoint: http://localhost:9000   # 운영: 빈 값 → 기본 AWS 엔드포인트
    region: us-east-1
    bucket: product-images
    access-key: minioadmin            # 로컬 전용. 운영은 env/IAM.
    secret-key: minioadmin
    presign-ttl-seconds: 300
    path-style: true                  # MinIO는 path-style 필요
  ```
  시크릿은 하드코딩 금지 — 로컬 기본값은 두되 운영 값은 env 오버라이드.

### 영역 2 — product-service 백엔드

**스키마 (Flyway `V5__add_price_and_images.sql`)**
```sql
ALTER TABLE product_sku ADD COLUMN price BIGINT NOT NULL DEFAULT 0;  -- 기존 행 0 백필, 시드에서 실가격

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
- V1 불변 원칙 준수 — 새 버전 V5로만. product-service 최신 마이그레이션은 V4이므로 V5.

**도메인/영속**
- `ProductSku` 엔티티에 `price` 필드 + `ProductSku.create(...)` 시그니처에 price 추가.
- 신규 `ProductImage` 도메인 엔티티(id, productId, s3Key, sortOrder, createdAt) — domain 레이어라 Spring/JPA 어노테이션 금지(기존 컨벤션 따름).
- `ProductImageRepository` 인터페이스(application/interfaces) + infrastructure 구현: `findByProductIdOrderBySortOrder`, `save`, `deleteByIdAndProductId`, `updateOrder`(배치).

**S3 포트/어댑터**
- `application/interfaces`에 `ObjectStoragePort` 정의 — 도메인이 AWS SDK에 직접 의존하지 않게:
  ```
  PresignedUpload presignUpload(String key, String contentType)   // {uploadUrl}
  String          presignDownload(String key)                     // GET URL
  boolean         exists(String key)                              // HEAD
  void            delete(String key)
  ```
- `infrastructure`에 `S3ObjectStorageAdapter`(S3Presigner + S3Client 사용, config 빈으로 주입). 엔드포인트/자격/path-style는 위 config에서.

**엔드포인트 (presentation)**
| 메서드·경로 | 인증 | 요청 | 응답 | 비고 |
|---|---|---|---|---|
| `GET /v1/products/{id}` | 공개 | — | `{id,name,category[],imageUrls[],skus[{skuCode,optionSummary,price,availableQty}]}` | imageUrls=presigned GET, sort_order 순 |
| `GET /v1/categories/{id}/products` | 공개 | page,size | `{content:[{id,name,minPrice,thumbnailUrl}],page,size,totalElements}` | thumbnailUrl=첫 이미지 presigned GET(없으면 null) |
| `GET /v1/categories` | 공개 | — | 트리(기존) | 변경 없음 |
| `POST /v1/products/{id}/images:presign` | ADMIN | `{contentType}` | `{key,uploadUrl}` | key=`products/{id}/{uuid}` |
| `POST /v1/products/{id}/images` | ADMIN | `{key,sortOrder?}` | `{imageId}` | S3 `exists(key)` 확인 실패 시 400. sortOrder 미지정 시 max+1 |
| `DELETE /v1/products/{id}/images/{imageId}` | ADMIN | — | 204 | DB 행 삭제 + S3 delete(멱등) |
| `PUT /v1/products/{id}/images/order` | ADMIN | `{imageIds:[...]}` | 200 | 주어진 순서대로 sort_order 재배치. 해당 상품 소속 이미지만 |

- **minPrice / imageUrls 조회**: 목록은 N+1 피하려 상품별 min(price)·첫 이미지 key를 조인/배치로. presign은 앱에서 key→URL 변환(네트워크 호출 없음, 서명 계산만).
- **권한 가드**: 게이트웨이가 넣어준 `X-User-Role` 헤더를 write 엔드포인트에서 확인. `ADMIN` 아니면 403. 현재 product-service엔 인가가 없으므로 신규 — 작은 인터셉터/필터 또는 컨트롤러 진입 가드 하나. (payment의 역할 인가 패턴 참고하되 product는 상품 단위 소유 개념 없이 ADMIN 단일 게이트.)

**시드 경로 확장**
- `SeedRequest.SkuLine` + `CatalogService.SkuSeed` + `ProductSku.create`에 `price` 추가. `POST /v1/products`는 게이트웨이 미노출 유지(관리/시드는 :8084 직접).

### 영역 3 — api-gateway

- `application.yml`: `gateway.downstream.product-uri: http://localhost:8084`.
- `RouteConfig`에 라우트 2개:
  - **공개 브라우징**: `GET /v1/products/**` `OR` `GET /v1/categories/**` → product-uri, 신뢰헤더 **strip**(user-auth-public 패턴 재사용). 메서드 predicate로 GET만(POST/DELETE/PUT 미포함).
  - **인증 이미지 write**: `POST /v1/products/*/images:presign`, `POST /v1/products/*/images`, `DELETE /v1/products/*/images/*`, `PUT /v1/products/*/images/order` → product-uri, `JwtTrustHeaderFilter` 부착(strip→verify→inject).
- 파일 바이트 PUT은 게이트웨이를 거치지 않는다(프론트 → MinIO presigned URL 직접). 게이트웨이는 presign 발급·키 기록만 중계.
- CSRF: gateway `CsrfFilter`가 상태변경 메서드에 CSRF를 요구하는지 확인 — 이미지 write(POST/DELETE/PUT)도 로그인 사용자이므로 기존 CSRF 규약(쿠키 토큰 + `X-CSRF-Token`) 적용. 프론트 api가 이미 `csrf` 옵션 지원.

### 영역 4 — frontend

**`src/api.js`** — 추가:
```
categories()                         GET /v1/categories
productsByCategory(id, page)         GET /v1/categories/{id}/products
product(id)                          GET /v1/products/{id}
presignImage(id, contentType)        POST .../images:presign   (csrf)
putToS3(uploadUrl, file)             fetch PUT 직접(credentials 없이, S3로)
confirmImage(id, key, sortOrder?)    POST .../images           (csrf)
deleteImage(id, imageId)             DELETE .../images/{imageId} (csrf)
reorderImages(id, imageIds)          PUT .../images/order       (csrf)
```
- `putToS3`는 게이트웨이가 아니라 반환된 `uploadUrl`로 직접 PUT, `Content-Type` 헤더 일치, `credentials: 'omit'`(쿠키 안 보냄).

**`src/App.jsx`** 재구성(컴포넌트 분리):
- `NavBar` — 브랜드 좌측, 우측 로그인/로그아웃. 로그인/회원가입은 모달(기존 폼 재사용). `me` 상태는 App가 보유(`api.me()`).
- `Home` — `categories()`로 탭 구성(트리에서 leaf 노드만 상품 조회 대상). 선택 카테고리의 `productsByCategory` → 상품 그리드 카드(썸네일 or placeholder · 이름 · `₩{minPrice}~`). 페이지네이션.
- `ProductDetail` — `product(id)` → 이미지 갤러리(다중, sort 순) + 카테고리 경로 + SKU 표(옵션·가격·재고). `me.role==='ADMIN'`이면 **관리 패널**: 파일 선택 → presign → putToS3 → confirm → 새로고침 / 이미지별 삭제 버튼 / 드래그 or 위·아래 버튼으로 순서변경 → reorder.
- 라우팅: 의존성 추가 없이 간단히(상태 기반 view 전환 또는 이미 있으면 react-router). **기본은 상태 기반**(YAGNI, 페이지 3개).

---

## 데이터 플로우

**브라우징(공개)**: 프론트 → 게이트웨이(GET, strip) → product-service → (min price 조인 · 첫 이미지 key → presign GET URL) → 카드.

**업로드(ADMIN)**:
1. 프론트 `presignImage` → 게이트웨이(POST, JWT verify+inject) → product-service: key 생성 + `ObjectStoragePort.presignUpload` → `{key, uploadUrl}`.
2. 프론트 `putToS3(uploadUrl, file)` → **MinIO 직접 PUT**(게이트웨이 우회).
3. 프론트 `confirmImage(key)` → 게이트웨이 → product-service: `exists(key)` 확인 후 `product_image` INSERT.
4. 프론트 상세 새로고침 → presigned GET URL로 갤러리 표시.

**삭제/순서변경(ADMIN)**: DB 우선(행 삭제/재배치) + S3 delete(삭제 시). S3 delete 실패는 로그 후 계속(고아 객체는 비치명적 — `// ponytail: 고아 스캐너는 필요해지면`).

## 에러 처리

- presign: 상품 없음 404, contentType 누락 400.
- confirm: `exists(key)` false → 400(업로드 안 됐거나 키 위조). 이미 등록된 key(UK 충돌) → 409 또는 멱등 처리.
- 권한: write에 ADMIN 아님 → 403(게이트웨이 통과했어도 product가 재확인).
- 브라우저 PUT 실패(CORS/네트워크): 프론트에서 사용자에게 표시, confirm 호출 안 함.
- 가격 백필: 기존 SKU price=0 → 목록 minPrice 0 표시 가능. 시드 재적용 전까지는 정상 동작(무결성 문제 아님).

## 테스트 전략

- **product-service 단위/통합**: SKU price 저장·조회, 목록 minPrice 계산, 이미지 등록/삭제/순서 재배치, `exists` 검증 분기, ADMIN 가드(403). S3는 `ObjectStoragePort` 목으로 단위, presign 실동작은 Testcontainers MinIO로 통합 1개.
- **gateway**: 공개 GET 라우팅 + strip, 이미지 write 라우트 JWT 필터 부착, GET 외 메서드가 공개 라우트에 안 걸림, presign/confirm 경로 통과.
- **frontend E2E(Playwright)**: 비로그인 그리드 조회 → 상세, 로그인 후 ADMIN 업로드(presign→PUT(MinIO)→confirm→갤러리 반영)·삭제·순서변경. 핵심 저니만.

## 마이그레이션/롤아웃

- Flyway V5는 product-service DB에만. `ADD COLUMN ... DEFAULT 0`로 기존 행 안전 백필.
- docker-compose에 MinIO 추가 → `docker compose up -d`로 로컬 확보. 버킷 생성 멱등.
- 게이트웨이 라우트 추가는 무상태·역호환(기존 라우트 불변).
- **취소 코어·인증 경계·재고 예약 로직 전부 불변** — 이 작업은 product 조회/이미지 + 게이트웨이 라우트 + 프론트뿐. TX3/멱등/스케줄러/outbox 무변경.

## 오픈 이슈 / 후속

- 프론트 오리진이 5173 외(빌드 배포)면 MinIO CORS·프론트 BASE 조정 필요.
- 운영 배포 시 실 S3 버킷·IAM·CORS는 인프라 작업으로 분리(이 스펙은 로컬 MinIO 기준, 엔드포인트 config 스위치만 준비).
- 이미지 대체 텍스트(alt)·접근성은 프론트에서 name 기반 기본 제공.
