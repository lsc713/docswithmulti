package com.example.order.domain.service;

import com.example.order.domain.entity.Order;
import com.example.order.domain.entity.OrderItem;

import java.util.List;

/**
 * 주문 아이템 검증 도메인 판정 (순수 POJO — Spring/JPA 어노테이션·repository 의존 없음).
 *
 * 판정: 요청된 orderItemId 전부 존재 && 전부 단일 order 소속 && order.userId == 요청자.
 * (docs/superpowers/specs/2026-07-31-order-link-design.md §3)
 *
 * Task 1: happy path만 구현. 실패 분기(누락/복수 order/소유불일치)는 Task 2에서 추가.
 */
public class OrderItemVerifier {

    public long resolveOrderId(List<Long> requestedIds, List<OrderItem> foundItems) {
        return foundItems.get(0).getOrderId();
    }

    public void checkOwnership(Order order, long requesterUserId) {
        // happy path: 소유 일치 — 아무 것도 하지 않음
    }
}
