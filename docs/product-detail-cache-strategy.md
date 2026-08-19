# Product 상세 캐시 전략

## 정책

Product 상세는 Redis cache-aside를 사용한다. 캐시 envelope의 `softExpiresAt`과 `hardExpiresAt`로 상태를 나눈다.

- `MISS`: 키가 없거나 Redis 조회/역직렬화 실패
- `FRESH`: 즉시 반환
- `STALE`: 기존 응답을 즉시 반환하고 상품 키별 Redisson 락으로 백그라운드 refresh
- `EXPIRED`: 락을 획득한 한 요청만 DB를 조회하고 저장

soft TTL에는 jitter를 적용해 인기 키가 동시에 만료되지 않게 한다. Redis 장애 시 DB 조회로 graceful fallback한다.

## 가격·재고

상세 응답에 포함된 가격과 표시용 재고는 캐시될 수 있지만, 예약·차감 가능 여부는 캐시를 사용하지 않는다. 실제 주문 경로는 MySQL의 원자 조건부 UPDATE를 최종 진실로 사용한다. 상품·가격·재고 변경 시 `ProductDetailCacheInvalidation`의 상품 키 무효화를 호출한다.

가격·재고의 더 짧은 TTL 또는 별도 Redis 키가 필요해지는 시점은 stale 가격/재고가 업무적으로 허용되지 않을 때다. 그 경우 콘텐츠 캐시와 동적 SKU 상태 캐시를 분리하고 변경 이벤트에서 동적 키를 즉시 삭제한다.

## 관측 지표

운영 전환 시 `cache_hit`, `cache_miss`, `cache_stale`, `cache_refresh`, Redis 오류율과 MySQL CPU/Hikari pending을 함께 본다. 목표는 상세 요청의 캐시 hit 증가와 DB CPU·pending 감소이며, 재고 예약 실패율이 증가하지 않아야 한다.
