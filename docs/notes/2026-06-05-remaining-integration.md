# 연동 작업 메모 (2026-06-05)

~~product-service 구현 후 남는 모듈 간 연동 작업.~~
**전체 완료 (2026-06-06)**

---

## ~~1. payment-service `payment.cancelled` 페이로드 확장~~ ✅ 완료

현재 페이로드:
```json
{
  "cancelRequestId": 1,
  "paymentKey": "pay_abc123",
  "merchantId": 100,
  "cancelledItems": [
    { "paymentItemId": 1, "orderItemId": 10, "itemAmount": 30000 }
  ],
  "cancelledAt": "2026-06-05T12:00:00Z"
}
```

필요한 확장:
```json
{
  "cancelledItems": [
    {
      "paymentItemId": 1,
      "orderItemId": 10,
      "itemAmount": 30000,
      "skuId": 5,        ← 추가
      "quantity": 2       ← 추가
    }
  ]
}
```

**변경 대상:**
- `payment-service` PaymentItem 엔티티에 `skuId`, `quantity` 필드 추가
- `payment-service` PaymentItem DDL (Flyway 새 버전)
- `payment-service` CancelTxWriter에서 Kafka 페이로드 생성 시 skuId/quantity 포함
- `payment-service` CreatePaymentRequest에 skuId/quantity 필드 추가

---

## ~~2. order-service → product-service 재고 차감 연동~~ ✅ 완료

주문 생성 시 product-service에 HTTP 동기 호출로 재고 차감.

**변경 대상:**
- `order-service` build.gradle에 Circuit Breaker 의존성 추가
- `order-service` application.yml에 product-service URL 추가
- `order-service` ProductStockClient 인터페이스 (application/interfaces)
- `order-service` ProductStockHttpClient 구현 (infrastructure/http)
- `order-service` CreateOrderService에서 주문 생성 전 재고 차감 호출
- 재고 부족 시 주문 생성 실패 처리

**호출 API:**
```
POST http://product-service:8084/internal/stocks/deduct
Body: { "skuId": 5, "quantity": 2 }
```

---

## ~~3. payment-service 결제 생성 시 skuId 저장~~ ✅ 완료

결제 생성 요청에 skuId를 받아 PaymentItem에 저장.
취소 시 페이로드에 skuId를 포함하기 위한 선행 작업.

**변경 대상:**
- `payment-service` CreatePaymentItemRequest에 skuId 필드 추가
- `payment-service` CreatePaymentCommand.Item에 skuId 필드 추가
- `payment-service` PaymentItem 엔티티에 skuId 필드 추가
- `payment-service` payment_item DDL (Flyway 새 버전)

---

## 테이블 참조 관계 — product/product_sku/product_stock이 다른 서비스에서 어떻게 참조되는가

```
[product-service]                  [payment-service]              [order-service]
                                                                 
product.id  ──────────────────→  payment_item.product_id       order_item.product_id
  │                                                              
  └── product_version.id  ───→  payment_item.product_auto_id   
        │                        (결제 시점 버전 스냅샷)          
        │                        item_name, item_amount도        
        │                        이 시점에 고정됨                 
        │                                                        
        └── product_sku.id  ──→  payment_item.sku_id (향후 추가)  order → deduct 시 참조
              │                                                   
              └── product_stock                                   
                    (SKU별 재고)                                   
                    - 차감: order-service가 /internal/stocks/deduct 호출
                    - 복원: payment.cancelled Kafka 이벤트 소비

정리:
  product.id        = 상품 원본 식별자 (불변)
  product_version.id = 특정 시점의 상품 정보 스냅샷 (이름/가격이 변경되면 새 버전)
  product_sku.id     = 특정 버전의 color+size 조합 (재고 관리 단위)
  product_stock      = SKU당 1:1, 실제 재고 수량

  다른 서비스는 product.id로 상품을 식별하고,
  product_version.id로 결제 시점 정보를 고정하고,
  product_sku.id로 재고를 차감/복원한다.
```
