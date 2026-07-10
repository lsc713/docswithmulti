package com.example.common.observability.querycount;

import net.ttddyy.dsproxy.QueryCountHolder;

/**
 * datasource-proxy의 스레드 로컬 QueryCountHolder에서 현재 스레드 누적 쿼리 수를 읽는다.
 */
public class DataSourceProxyQueryCountReader implements QueryCountReader {

    @Override
    public long readAndReset() {
        long total = QueryCountHolder.getGrandTotal().getTotal();
        QueryCountHolder.clear();
        return total;
    }
}
