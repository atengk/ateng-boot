package io.github.atengk.boot.observability.health;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * DynamicDatasourceHealthIndicator 多数据源健康探针单元测试
 *
 * @author Ateng
 * @since 2026-10-22
 */
@DisplayName("DynamicDatasourceHealthIndicator 多数据源健康探针单元测试")
class DynamicDatasourceHealthIndicatorTest {

    @Test
    @DisplayName("未装配任何 DataSource 时，返回 UNKNOWN 状态")
    void testNoDataSourceConfigured() {
        ApplicationContext mockContext = mock(ApplicationContext.class);
        when(mockContext.getBeansOfType(DataSource.class)).thenReturn(Collections.emptyMap());

        DynamicDatasourceHealthIndicator indicator = new DynamicDatasourceHealthIndicator(mockContext);
        org.springframework.boot.actuate.health.Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(org.springframework.boot.actuate.health.Status.UNKNOWN);
        assertThat(health.getDetails()).containsEntry("message", "未检测到已装配的 DataSource 实例");
    }

    @Test
    @DisplayName("标准数据源连通探测成功，返回 UP 状态与数据库元数据")
    void testSingleDataSourceUp() throws Exception {
        DataSource mockDs = mock(DataSource.class);
        Connection mockConn = mock(Connection.class);
        DatabaseMetaData mockMeta = mock(DatabaseMetaData.class);

        when(mockDs.getConnection()).thenReturn(mockConn);
        when(mockConn.isValid(1)).thenReturn(true);
        when(mockConn.getMetaData()).thenReturn(mockMeta);
        when(mockMeta.getDatabaseProductName()).thenReturn("MySQL");
        when(mockMeta.getDatabaseProductVersion()).thenReturn("8.0.36");

        ApplicationContext mockContext = mock(ApplicationContext.class);
        when(mockContext.getBeansOfType(DataSource.class)).thenReturn(Map.of("masterDataSource", mockDs));

        DynamicDatasourceHealthIndicator indicator = new DynamicDatasourceHealthIndicator(mockContext);
        org.springframework.boot.actuate.health.Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(org.springframework.boot.actuate.health.Status.UP);
        assertThat(health.getDetails()).containsEntry("dataSourceCount", 1);

        @SuppressWarnings("unchecked")
        Map<String, Object> pools = (Map<String, Object>) health.getDetails().get("dataSources");
        @SuppressWarnings("unchecked")
        Map<String, Object> masterInfo = (Map<String, Object>) pools.get("masterDataSource");
        assertThat(masterInfo).containsEntry("status", "UP");
        assertThat(masterInfo).containsEntry("databaseProductName", "MySQL");
        assertThat(masterInfo).containsEntry("databaseProductVersion", "8.0.36");
    }

    @Test
    @DisplayName("数据源连接获取失败，返回 DOWN 状态并记录原因")
    void testDataSourceConnectionFailureDown() throws Exception {
        DataSource mockDs = mock(DataSource.class);
        when(mockDs.getConnection()).thenThrow(new java.sql.SQLException("Connection refused: 192.168.1.10:3306"));

        ApplicationContext mockContext = mock(ApplicationContext.class);
        when(mockContext.getBeansOfType(DataSource.class)).thenReturn(Map.of("brokenDataSource", mockDs));

        DynamicDatasourceHealthIndicator indicator = new DynamicDatasourceHealthIndicator(mockContext);
        org.springframework.boot.actuate.health.Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(org.springframework.boot.actuate.health.Status.DOWN);
        @SuppressWarnings("unchecked")
        Map<String, Object> pools = (Map<String, Object>) health.getDetails().get("dataSources");
        @SuppressWarnings("unchecked")
        Map<String, Object> brokenInfo = (Map<String, Object>) pools.get("brokenDataSource");
        assertThat(brokenInfo).containsEntry("status", "DOWN");
        assertThat((String) brokenInfo.get("error")).contains("Connection refused");
    }

    @Test
    @DisplayName("多数据源环境探测：一主一从均连通返回 UP，并标记 multiDataSourceDetected=true")
    void testMultipleDataSourcesUp() throws Exception {
        DataSource ds1 = mock(DataSource.class);
        Connection conn1 = mock(Connection.class);
        when(ds1.getConnection()).thenReturn(conn1);
        when(conn1.isValid(1)).thenReturn(true);
        DatabaseMetaData meta1 = mock(DatabaseMetaData.class);
        when(conn1.getMetaData()).thenReturn(meta1);
        when(meta1.getDatabaseProductName()).thenReturn("PostgreSQL");
        when(meta1.getDatabaseProductVersion()).thenReturn("16.2");

        DataSource ds2 = mock(DataSource.class);
        Connection conn2 = mock(Connection.class);
        when(ds2.getConnection()).thenReturn(conn2);
        when(conn2.isValid(1)).thenReturn(true);
        DatabaseMetaData meta2 = mock(DatabaseMetaData.class);
        when(conn2.getMetaData()).thenReturn(meta2);
        when(meta2.getDatabaseProductName()).thenReturn("PostgreSQL");
        when(meta2.getDatabaseProductVersion()).thenReturn("16.2");

        Map<String, DataSource> dsMap = new LinkedHashMap<>();
        dsMap.put("master", ds1);
        dsMap.put("slave", ds2);

        ApplicationContext mockContext = mock(ApplicationContext.class);
        when(mockContext.getBeansOfType(DataSource.class)).thenReturn(dsMap);

        DynamicDatasourceHealthIndicator indicator = new DynamicDatasourceHealthIndicator(mockContext, Duration.ofSeconds(1), null);
        org.springframework.boot.actuate.health.Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(org.springframework.boot.actuate.health.Status.UP);
        assertThat(health.getDetails()).containsEntry("multiDataSourceDetected", true);
        assertThat(health.getDetails()).containsEntry("dataSourceCount", 2);
    }
}
