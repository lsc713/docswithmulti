package com.example.common.observability.querycount;

import net.ttddyy.dsproxy.QueryCountHolder;
import net.ttddyy.dsproxy.listener.DataSourceQueryCountListener;
import net.ttddyy.dsproxy.support.ProxyDataSourceBuilder;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;

import static org.assertj.core.api.Assertions.assertThat;

class DataSourceProxyQueryCountReaderTest {

    @Test
    void countsExecutedQueriesAndResets() throws Exception {
        JdbcDataSource raw = new JdbcDataSource();
        raw.setURL("jdbc:h2:mem:qctest;DB_CLOSE_DELAY=-1");

        // 스키마 준비는 raw로 (카운트 대상 아님)
        try (Connection c = raw.getConnection()) {
            c.createStatement().execute("CREATE TABLE t(id INT)");
        }

        DataSource proxy = ProxyDataSourceBuilder.create(raw)
                .name("main")
                .listener(new DataSourceQueryCountListener())
                .build();

        QueryCountHolder.clear();
        try (Connection c = proxy.getConnection()) {
            for (int i = 0; i < 3; i++) {
                c.createStatement().executeQuery("SELECT 1");
            }
        }

        QueryCountReader reader = new DataSourceProxyQueryCountReader();
        assertThat(reader.readAndReset()).isEqualTo(3L);
        // 리셋 확인
        assertThat(reader.readAndReset()).isEqualTo(0L);
    }
}
