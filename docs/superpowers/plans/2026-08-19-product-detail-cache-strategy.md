# Product Detail Cache Strategy Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Product 상세 조회에 Redis cache-aside, 인기 상품 refresh-ahead, stale-while-revalidate, single-flight 갱신과 가격·재고 신선도 정책을 추가한다.

**Architecture:** 상세 콘텐츠는 Redis envelope의 soft/hard TTL로 fresh/stale/miss를 구분한다. 캐시 miss와 stale 갱신은 상품 키별 Redisson 분산락으로 single-flight 처리한다. 가격과 재고는 콘텐츠 캐시와 별도 키·TTL로 관리하고, 예약/차감은 캐시를 사용하지 않는다.

**Tech Stack:** Spring Boot, Redisson, Redis, Jackson, JUnit 5, Mockito.

**Spec:** `docs/load-test/product-detail-query-analysis-2026-08-19.md`, `docs/load-test/product-detail-query-aws-results-2026-08-19.md`

## Global Constraints

- 재고 예약·차감의 정합성 판단은 항상 MySQL 원자 UPDATE를 기준으로 한다.
- 캐시 miss/stale 동시 요청은 단일 DB 조회로 제한한다.
- 가격·재고 변경 시 관련 상품 캐시를 무효화한다.
- Redis 장애 시 기존 DB 조회 경로로 graceful fallback한다.

### Task 1: Cache state and policy tests

**Files:**
- Create: `product-service/src/main/java/com/example/product/infrastructure/cache/ProductDetailCacheState.java`
- Create: `product-service/src/main/java/com/example/product/infrastructure/cache/ProductDetailCachePolicy.java`
- Test: `product-service/src/test/java/com/example/product/infrastructure/cache/ProductDetailCachePolicyTest.java`

- [x] **Step 1: Write failing tests** for fresh, stale, expired, and missing state classification, plus jittered TTL bounds.
- [x] **Step 2: Run the focused test and verify it fails** because the policy types do not exist.
- [x] **Step 3: Implement the minimal immutable state/policy types** with soft TTL and hard TTL.
- [x] **Step 4: Run the focused test and verify it passes.**

### Task 2: Redis cache service with single-flight

**Files:**
- Create: `product-service/src/main/java/com/example/product/infrastructure/cache/ProductDetailCacheService.java`
- Test: `product-service/src/test/java/com/example/product/infrastructure/cache/ProductDetailCacheServiceTest.java`

- [x] **Step 1: Write failing tests** for fresh hit, stale response plus one async refresh, miss single-flight, and Redis failure fallback.
- [x] **Step 2: Run the focused test and verify it fails.**
- [x] **Step 3: Implement cache envelope serialization, Redisson `RLock`, and refresh callback.**
- [x] **Step 4: Run the focused test and verify it passes.**

### Task 3: Integrate Product detail and freshness layers

**Files:**
- Modify: `product-service/src/main/java/com/example/product/application/service/ProductQueryService.java`
- Create: `product-service/src/main/java/com/example/product/infrastructure/cache/ProductDetailCacheInvalidation.java`
- Test: `product-service/src/test/java/com/example/product/application/service/ProductQueryServiceCacheTest.java`

- [ ] **Step 1: Write failing tests** proving cache hit skips repositories, miss loads once, stale serves old content, and price/stock are not used for reservation decisions.
- [ ] **Step 2: Run the focused test and verify it fails.**
- [x] **Step 3: Integrate the cache around the existing detail assembler and expose invalidation by product id.**
- [x] **Step 4: Add price/stock invalidation hooks without changing reservation writes; keep the DB authoritative for reservation decisions.**
- [x] **Step 5: Run focused and existing Product tests.**

### Task 4: Configuration, observability, and documentation

**Files:**
- Modify: `product-service/src/main/resources/application.yml`
- Modify: `product-service/src/test/resources/application.yml`
- Modify: `docs/load-test/product-detail-query-analysis-2026-08-19.md`
- Create: `docs/product-detail-cache-strategy.md`

- [x] **Step 1: Add configurable TTL, stale window, lock wait, and cache key settings with safe defaults.**
- [ ] **Step 2: Add cache hit/miss/stale/refresh metrics.**
- [x] **Step 3: Document content/price/stock policies, invalidation events, and cache stampede handling.**
- [x] **Step 4: Run focused Product tests and `git diff --check`; the full suite remains to be rerun.**
