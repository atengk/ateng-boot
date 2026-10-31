package io.github.atengk.boot.sample.datasource;

import com.baomidou.dynamic.datasource.creator.DataSourceProperty;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.datasource.loader.DynamicDataSourceLoader;
import io.github.atengk.boot.datasource.loader.SyncResult;
import io.github.atengk.boot.datasource.model.DataSourcePoolStats;
import io.github.atengk.boot.datasource.readwrite.ReadAfterWriteManager;
import io.github.atengk.boot.sample.datasource.entity.OrderEntity;
import io.github.atengk.boot.sample.datasource.service.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 动态数据源全场景生产加固与自愈治理端到端集成测试
 * <p>
 * 严守非 Web 组件底座契约（零 Dummy Controller 污染），依托专职 ScenarioService 进行全链路实测：
 * <ul>
 *     <li>1. 租户多从库分组路由与写后读主防延迟闭环 (DatasourceTenantReadWriteScenarioService)</li>
 *     <li>2. 从库故障熔断隔离与后台自愈恢复 (DatasourceActiveProbingScenarioService)</li>
 *     <li>3. 动态数据源挂载连接池并发预热 (DatasourcePreWarmScenarioService)</li>
 *     <li>4. 连接池运行指标快照与 Micrometer Gauges 监控观测 (DatasourceMetricsScenarioService)</li>
 *     <li>5. 外部数据源动态加载与智能三向差量同步 (DatasourceDynamicLoaderScenarioService)</li>
 * </ul>
 *
 * @author Ateng
 * @since 2026-10-31
 */
@SpringBootTest
@Import(DatasourceHardenedScenariosIntegrationTest.TestLoaderConfiguration.class)
@DisplayName("动态数据源全场景生产加固端到端集成测试")
class DatasourceHardenedScenariosIntegrationTest {

    @Autowired
    private DatasourceTenantReadWriteScenarioService tenantReadWriteScenarioService;

    @Autowired
    private DatasourceActiveProbingScenarioService activeProbingScenarioService;

    @Autowired
    private DatasourcePreWarmScenarioService preWarmScenarioService;

    @Autowired
    private DatasourceMetricsScenarioService metricsScenarioService;

    @Autowired
    private DatasourceDynamicLoaderScenarioService dynamicLoaderScenarioService;

    @Autowired
    private ReadAfterWriteManager readAfterWriteManager;

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
        if (readAfterWriteManager != null) {
            readAfterWriteManager.clear();
        }
        activeProbingScenarioService.recoverReplica("slave");
    }

    @Test
    @DisplayName("场景 1: 租户多从库分组路由与写后读主一致性保障实测")
    void testTenantGroupReadWriteRoutingAndWriteAfterRead() {
        String tenantId = "1001";
        String orderNo = "ORD-TENANT-1001-001";

        // 1. 为租户 1001 配置从库分组 (tenant_1001_slave -> slave 物理节点)
        tenantReadWriteScenarioService.configureTenantSlaveGroup(tenantId, "tenant_1001_slave", "slave");

        // 2. 租户写入订单并触发写标记
        tenantReadWriteScenarioService.createOrderWithWriteTracking(tenantId, orderNo);
        assertThat(tenantReadWriteScenarioService.isWriteOccurredActive()).isTrue();

        // 3. 租户只读查询：在写后读主防延迟期内，系统强制自适应路由至租户主库 (tenant_1001)，读到刚写入的数据
        OrderEntity entity = tenantReadWriteScenarioService.queryOrderWithConsistency(tenantId, orderNo);
        assertThat(entity).isNotNull();
        assertThat(entity.getOrderNo()).isEqualTo(orderNo);
        assertThat(entity.getTenantId()).isEqualTo(tenantId);
    }

    @Test
    @DisplayName("场景 2: 从库故障感知、黑名单熔断隔离与手动/自动自愈恢复实测")
    void testActiveProbingAndFailureSelfHealing() {
        String replicaKey = "slave";

        // 1. 模拟从库物理节点连接失败
        activeProbingScenarioService.simulateReplicaFailure(replicaKey);
        assertThat(activeProbingScenarioService.isReplicaBlacklisted(replicaKey)).isTrue();
        assertThat(activeProbingScenarioService.getBlacklistedDataSources()).contains(replicaKey);

        // 2. 自愈恢复放行流量
        activeProbingScenarioService.recoverReplica(replicaKey);
        assertThat(activeProbingScenarioService.isReplicaBlacklisted(replicaKey)).isFalse();
    }

    @Test
    @DisplayName("场景 3: 动态数据源挂载与物理连接池并发预热实测")
    void testConnectionPoolPreWarming() {
        String dsKey = "tenant_prewarm";
        String jdbcUrl = "jdbc:h2:mem:prewarm_sample_db;DB_CLOSE_DELAY=-1";

        try {
            // 挂载并预热 3 个物理连接
            int warmed = preWarmScenarioService.registerAndPreWarm(dsKey, jdbcUrl, 3);
            assertThat(warmed).isGreaterThanOrEqualTo(1);

            // 检查连接池状态快照
            DataSourcePoolStats stats = preWarmScenarioService.inspectPoolStats(dsKey);
            assertThat(stats).isNotNull();
            assertThat(stats.getDsKey()).isEqualTo(dsKey);
            assertThat(stats.getTotalConnections()).isGreaterThanOrEqualTo(1);
        } finally {
            preWarmScenarioService.unregister(dsKey);
        }
    }

    @Test
    @DisplayName("场景 4: 连接池运行快照检查与 Micrometer Gauges 监控观测实测")
    void testPoolStatsAndMicrometerMetrics() {
        // 1. 验证 Micrometer 绑定器处于就绪状态
        assertThat(metricsScenarioService.isMetricsBinderActive()).isTrue();

        // 2. 检查主库连接池快照
        DataSourcePoolStats masterStats = metricsScenarioService.getPoolStats("master");
        assertThat(masterStats).isNotNull();
        assertThat(masterStats.getDsKey()).isEqualTo("master");
        assertThat(masterStats.getActiveConnections()).isZero();

        // 3. 检查 Micrometer Gauges 采样值
        Double activeGauge = metricsScenarioService.getMicrometerGaugeValue("datasource.connections.active", "master");
        assertThat(activeGauge).isEqualTo(0.0);

        Double totalGauge = metricsScenarioService.getMicrometerGaugeValue("datasource.connections.total", "master");
        assertThat(totalGauge).isNotNull();

        // 4. 检查全景指标映射表
        Map<String, DataSourcePoolStats> allStats = metricsScenarioService.getAllPoolStats();
        assertThat(allStats).containsKeys("master", "slave", "tenant_1001", "tenant_1002");
    }

    @Test
    @DisplayName("场景 5: 外部数据源动态加载、智能三向差量同步与核心白名单防护实测")
    void testDynamicDataSourceLoaderAndThreeWayReconciliation() {
        // 1. 核心数据源绝对白名单防护校验
        assertThat(dynamicLoaderScenarioService.isCoreProtected("master")).isTrue();
        assertThat(dynamicLoaderScenarioService.isCoreProtected("slave")).isTrue();
        assertThat(dynamicLoaderScenarioService.isCoreProtected("tenant_1001")).isFalse();

        // 2. 触发一次差量同步 (通过 TestLoaderConfiguration 提供的外部源)
        SyncResult result = dynamicLoaderScenarioService.triggerSync();
        assertThat(result).isNotNull();
        assertThat(result.getAddedCount()).isEqualTo(1);
        assertThat(result.getAddedKeys()).containsExactly("tenant_sync_sample");

        // 验证已纳管属性快照
        assertThat(dynamicLoaderScenarioService.getLoadedProperties()).containsKey("tenant_sync_sample");
    }

    @TestConfiguration
    static class TestLoaderConfiguration {

        @Bean
        public DynamicDataSourceLoader testDynamicDataSourceLoader() {
            return () -> {
                Map<String, DataSourceProperty> map = new LinkedHashMap<>();
                DataSourceProperty p = new DataSourceProperty();
                p.setPoolName("tenant_sync_sample");
                p.setUrl("jdbc:h2:mem:sync_sample_db;DB_CLOSE_DELAY=-1");
                p.setUsername("sa");
                p.setPassword("");
                p.setDriverClassName("org.h2.Driver");
                map.put("tenant_sync_sample", p);
                return map;
            };
        }
    }
}
