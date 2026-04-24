package com.example.order.application.exception;

/**
 * 재시도해도 해결될 수 없는 예외 (데이터 오류).
 * RetryRouter가 이 마커를 보고 즉시 DLQ로 발행한다.
 */
public interface NonRetryableException {}
