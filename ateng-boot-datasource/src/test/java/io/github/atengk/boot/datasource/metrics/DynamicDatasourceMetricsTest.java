package io.github.atengk.boot.datasource.metrics;

import com.baomidou.dynamic.datasource.creator.DataSourceProperty;
import com.baomidou.dynamic.datasource.creator.DefaultDataSourceCreator;
import com.baomidou.dynamic.datasource.creator.hikaricp.HikariCpConfig;
import com.baomidou.dynamic.datasource.creator.hikaricp.HikariDataSourceCreator;
import com.baomidou.dynamic.datasource.toolkit.DynamicDataSourceContextHolder;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.github.atengk.boot.datasource.model.DataSourcePoolStats;
import io.github.atengk.boot.datasource.properties.DatasourceProperties;
import io.github.atengk.boot.datasource.routing.AtengDynamicRoutingDataSource;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static io.github.atengk.boot.datasource.metrics.DynamicDatasourceMetricsBinder.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 动态多数据源运行指标快照 DTO 与 Micrometer 自动透视绑定器单元测试
 *
 * @author Ateng
 * @since 2026-10-31
 */
@DisplayName("动态多数据源指标监控与 Micrometer 自动透视测试")
class DynamicDatasourceMetricsTest {

    private AtengDynamicRoutingDataSource routingDataSource;
    private io.github.atengk.boot.datasource.template.DynamicDatasourceTemplate template;
    private MeterRegistry meterRegistry;
    private DynamicDatasourceMetricsBinder metricsBinder;
    private HikariDataSource masterHikariDs;

    @BeforeEach
    void setUp() {
        DynamicDataSourceContextHolder.clear();
        meterRegistry = new SimpleMeterRegistry();

        // 构造底层真实 Hikari 连接池 (H2 内存库)
        HikariConfig hikariConfig = new HikariConfig();
        hikariConfig.setPoolName("HikariPool-Master");
        hikariConfig.setJdbcUrl("jdbc:h2:mem:master_metrics_test;DB_CLOSE_DELAY=-1");
        hikariConfig.setUsername("sa");
        hikariConfig.setPassword("");
        hikariConfig.setDriverClassName("org.h2.Driver");
        hikariConfig.setMaximumPoolSize(10);
        hikariConfig.setMinimumIdle(2);
        masterHikariDs = new HikariDataSource(hikariConfig);

        routingDataSource = new AtengDynamicRoutingDataSource(
                Collections.emptyList(),
                new DatasourceProperties(),
                null,
                null,
                null
        );
        routingDataSource.addDataSource("master", masterHikariDs);
        routingDataSource.setPrimary("master");

        // 构造 DefaultDataSourceCreator
        DefaultDataSourceCreator defaultDataSourceCreator = new DefaultDataSourceCreator();
        HikariDataSourceCreator hikariCreator = new HikariDataSourceCreator(new HikariCpConfig());
        defaultDataSourceCreator.setCreators(List.of(hikariCreator));

        DatasourceProperties properties = new DatasourceProperties();
        properties.getDynamicLoad().setProbeTimeout(Duration.ofSeconds(1));
        properties.getDynamicLoad().setDrainTimeout(Duration.ofSeconds(1));

        template = new io.github.atengk.boot.datasource.template.DynamicDatasourceTemplate(
                routingDataSource,
                properties,
                null,
                null,
                defaultDataSourceCreator
        );

        metricsBinder = new DynamicDatasourceMetricsBinder(template);
    }

    @AfterEach
    void tearDown() {
        DynamicDataSourceContextHolder.clear();
        if (metricsBinder != null) {
            metricsBinder.destroy();
        }
        if (routingDataSource != null) {
            routingDataSource.destroy();
        }
        if (masterHikariDs != null && !masterHikariDs.isClosed()) {
            masterHikariDs.close();
        }
        if (meterRegistry != null) {
            meterRegistry.close();
        }
    }

    @Test
    @DisplayName("测试 HikariCP 连接池指标快照 DTO 准确提取")
    void shouldAccuratelyExtractPoolStatsFromHikariDataSource() throws Exception {
        DataSourcePoolStats stats = template.getPoolStats("master");
        assertThat(stats).isNotNull();
        assertThat(stats.getDsKey()).isEqualTo("master");
        assertThat(stats.getPoolName()).isEqualTo("HikariPool-Master");
        assertThat(stats.getMaximumPoolSize()).isEqualTo(10);
        assertThat(stats.getMinimumIdle()).isEqualTo(2);
        assertThat(stats.getActiveConnections()).isZero();
        assertThat(stats.getThreadsAwaitingConnection()).isZero();

        // 借用连接验证活跃连接指标实时感知
        try (Connection conn = masterHikariDs.getConnection()) {
            DataSourcePoolStats activeStats = template.getPoolStats("master");
            assertThat(activeStats).isNotNull();
            assertThat(activeStats.getActiveConnections()).isEqualTo(1);
            assertThat(activeStats.getTotalConnections()).isGreaterThanOrEqualTo(1);
        }

        // 归还连接后活跃连接重置为 0
        DataSourcePoolStats idleStats = template.getPoolStats("master");
        assertThat(idleStats).isNotNull();
        assertThat(idleStats.getActiveConnections()).isZero();
    }

    @Test
    @DisplayName("测试非 Hikari 数据源与未知 Key 状态快照提取的安全保底")
    void shouldReturnSafeStatsForNonHikariOrUnknownKeys() {
        // 添加普通 JdbcDataSource
        JdbcDataSource rawDs = new JdbcDataSource();
        rawDs.setURL("jdbc:h2:mem:raw_test;DB_CLOSE_DELAY=-1");
        routingDataSource.addDataSource("raw_ds", rawDs);

        DataSourcePoolStats rawStats = template.getPoolStats("raw_ds");
        assertThat(rawStats).isNotNull();
        assertThat(rawStats.getDsKey()).isEqualTo("raw_ds");
        assertThat(rawStats.getActiveConnections()).isZero();
        assertThat(rawStats.getIdleConnections()).isZero();
        assertThat(rawStats.getTotalConnections()).isZero();

        // 未知 Key 返回 null，防 NPE
        assertThat(template.getPoolStats("unknown_ds")).isNull();
        assertThat(template.getPoolStats("")).isNull();
        assertThat(template.getPoolStats(null)).isNull();

        // 获取全部数据源状态快照
        Map<String, DataSourcePoolStats> allStats = template.getAllPoolStats();
        assertThat(allStats).containsKeys("master", "raw_ds");
        assertThat(allStats.get("master").getMaximumPoolSize()).isEqualTo(10);
    }

    @Test
    @DisplayName("测试初始数据源指标自动绑定至 Micrometer Registry 并支持动态采样")
    void shouldBindInitialMetersToMicrometerRegistry() throws Exception {
        metricsBinder.bindTo(meterRegistry);

        assertThat(metricsBinder.isBound("master")).isTrue();
        assertThat(metricsBinder.getMonitoredKeys()).contains("master");

        // 检查 Gauge 是否已成功注册
        Gauge activeGauge = meterRegistry.find(METRIC_ACTIVE).tag(TAG_NAME, "master").gauge();
        Gauge idleGauge = meterRegistry.find(METRIC_IDLE).tag(TAG_NAME, "master").gauge();
        Gauge totalGauge = meterRegistry.find(METRIC_TOTAL).tag(TAG_NAME, "master").gauge();
        Gauge pendingGauge = meterRegistry.find(METRIC_PENDING_THREADS).tag(TAG_NAME, "master").gauge();

        assertThat(activeGauge).isNotNull();
        assertThat(idleGauge).isNotNull();
        assertThat(totalGauge).isNotNull();
        assertThat(pendingGauge).isNotNull();

        assertThat(activeGauge.value()).isEqualTo(0.0);
        assertThat(pendingGauge.value()).isEqualTo(0.0);

        // 借用连接并断言 Gauge 采样值发生动态联动变化
        try (Connection conn = masterHikariDs.getConnection()) {
            assertThat(activeGauge.value()).isEqualTo(1.0);
            assertThat(totalGauge.value()).isGreaterThanOrEqualTo(1.0);
        }

        // 归还连接后 Gauge 采样值恢复
        assertThat(activeGauge.value()).isEqualTo(0.0);
    }

    @Test
    @DisplayName("测试动态热加载数据源时自动注册对应 Gauges")
    void shouldAutomaticallyBindMetersWhenDataSourceDynamicallyAdded() {
        metricsBinder.bindTo(meterRegistry);

        DataSourceProperty property = new DataSourceProperty();
        property.setPoolName("dynamic_tenant_pool");
        property.setUrl("jdbc:h2:mem:dynamic_tenant_db;DB_CLOSE_DELAY=-1");
        property.setUsername("sa");
        property.setPassword("");
        property.setDriverClassName("org.h2.Driver");

        // 动态挂载数据源
        template.addDataSource("tenant_1", property);

        assertThat(metricsBinder.isBound("tenant_1")).isTrue();
        assertThat(metricsBinder.getMonitoredKeys()).contains("tenant_1");

        Gauge activeGauge = meterRegistry.find(METRIC_ACTIVE).tag(TAG_NAME, "tenant_1").gauge();
        Gauge idleGauge = meterRegistry.find(METRIC_IDLE).tag(TAG_NAME, "tenant_1").gauge();
        Gauge totalGauge = meterRegistry.find(METRIC_TOTAL).tag(TAG_NAME, "tenant_1").gauge();
        Gauge pendingGauge = meterRegistry.find(METRIC_PENDING_THREADS).tag(TAG_NAME, "tenant_1").gauge();

        assertThat(activeGauge).isNotNull();
        assertThat(idleGauge).isNotNull();
        assertThat(totalGauge).isNotNull();
        assertThat(pendingGauge).isNotNull();
    }

    @Test
    @DisplayName("测试动态摘除数据源时自动从 MeterRegistry 注销并清理 Gauges (防指标泄漏)")
    void shouldAutomaticallyUnbindMetersWhenDataSourceDynamicallyRemoved() {
        metricsBinder.bindTo(meterRegistry);

        DataSourceProperty property = new DataSourceProperty();
        property.setPoolName("temp_pool");
        property.setUrl("jdbc:h2:mem:temp_db;DB_CLOSE_DELAY=-1");
        property.setUsername("sa");
        property.setPassword("");
        property.setDriverClassName("org.h2.Driver");

        template.addDataSource("temp_ds", property);
        assertThat(metricsBinder.isBound("temp_ds")).isTrue();
        assertThat(meterRegistry.find(METRIC_ACTIVE).tag(TAG_NAME, "temp_ds").gauge()).isNotNull();

        // 动态注销数据源
        template.removeDataSource("temp_ds", false);

        assertThat(metricsBinder.isBound("temp_ds")).isFalse();
        assertThat(metricsBinder.getMonitoredKeys()).doesNotContain("temp_ds");

        // 验证 MeterRegistry 中的 Gauge 已经被彻底清除
        assertThat(meterRegistry.find(METRIC_ACTIVE).tag(TAG_NAME, "temp_ds").gauge()).isNull();
        assertThat(meterRegistry.find(METRIC_IDLE).tag(TAG_NAME, "temp_ds").gauge()).isNull();
        assertThat(meterRegistry.find(METRIC_TOTAL).tag(TAG_NAME, "temp_ds").gauge()).isNull();
        assertThat(meterRegistry.find(METRIC_PENDING_THREADS).tag(TAG_NAME, "temp_ds").gauge()).isNull();
    }

    @Test
    @DisplayName("测试销毁时安全释放所有数据源监控指标")
    void shouldCleanUpAllMetersOnDestroy() {
        metricsBinder.bindTo(meterRegistry);
        assertThat(metricsBinder.isBound("master")).isTrue();

        metricsBinder.destroy();

        assertThat(metricsBinder.isBound("master")).isFalse();
        assertThat(metricsBinder.getMonitoredKeys()).isEmpty();
        assertThat(meterRegistry.find(METRIC_ACTIVE).tag(TAG_NAME, "master").gauge()).isNull();
    }
}
