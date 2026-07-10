package com.example.common.observability.querycount;

/**
 * 현재 스레드(=요청 스레드)에 누적된 실행 쿼리 수를 읽고 카운터를 리셋한다.
 * 요청-스레드 1:1 + Hikari 블로킹 모델 전제.
 */
public interface QueryCountReader {
    /** 누적 쿼리 총수를 반환하고 카운터를 0으로 clear. */
    long readAndReset();
}
