package io.github.atengk.boot.datasource.strategy;

import com.baomidou.dynamic.datasource.toolkit.DynamicDataSourceContextHolder;
import io.github.atengk.boot.datasource.properties.DatasourceProperties;
import io.github.atengk.boot.datasource.routing.AtengDynamicRoutingDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

/**
 * 从库故障后台受管主动探活与渐进退避自愈引擎单元测试
 *
 * @author Ateng
 * @since 2026-10-31
 */
class HealthAwareDynamicDataSourceActiveProbeTest {

    private DatasourceProperties properties;
    private HealthAwareDynamicDataSourceStrategy strategy;

    @BeforeEach
    void setUp() {
        properties = new DatasourceProperties();
        properties.getReadWriteSplit().setMasterDataSource("master");
        DatasourceProperties.FailoverProperties failover = properties.getReadWriteSplit().getFailover();
        failover.setEnabled(true);
        failover.setActiveProbeEnabled(true);
        failover.setProbeInitialInterval(Duration.ofMillis(40));
        failover.setProbeMaxInterval(Duration.ofMillis(200));
        failover.setProbeTimeout(Duration.ofSeconds(1));
        failover.setCooldownWindow(Duration.ofSeconds(30));
        failover.setFallbackToMaster(true);

        strategy = new HealthAwareDynamicDataSourceStrategy(properties);
    }

    @AfterEach
    void tearDown() {
        if (strategy != null) {
            strategy.destroy();
        }
    }

    @Test
    @DisplayName("测试从库故障进入黑名单后触发后台主动探活并在探活成功后即刻自愈放行")
    void testActiveProbeTriggeredAndRestoredUponSuccess() throws Exception {
        DataSource mockDs = mock(DataSource.class);
        Connection mockConn = mock(Connection.class);

        AtomicBoolean healthy = new AtomicBoolean(false);
        when(mockDs.getConnection()).thenAnswer(inv -> {
            if (!healthy.get()) {
                throw new SQLException("Connection refused");
            }
            return mockConn;
        });
        when(mockConn.isValid(anyInt())).thenAnswer(inv -> healthy.get());

        strategy.setDataSourceLocator(key -> "slave_1".equals(key) ? mockDs : null);

        // 1. 记录 slave_1 故障
        strategy.recordFailure("slave_1");

        // 2. 校验即刻列入熔断黑名单并启动探活
        assertThat(strategy.isBlacklisted("slave_1")).isTrue();
        assertThat(strategy.isProbing("slave_1")).isTrue();
        assertThat(strategy.getBlacklistedDataSources()).containsExactly("slave_1");

        // 候选路由应过滤掉故障节点
        List<String> candidates = List.of("slave_1", "slave_2");
        assertThat(strategy.filterHealthyNodes(candidates)).containsExactly("slave_2");

        // 3. 模拟底层物理数据库自愈上线
        healthy.set(true);

        // 等待后台探活调度执行 (初始周期 40ms，轮询最多 500ms)
        for (int i = 0; i < 25 && strategy.isBlacklisted("slave_1"); i++) {
            Thread.sleep(20);
        }

        // 4. 校验主动探活成功后，即刻自愈解除黑名单并恢复放行
        assertThat(strategy.isBlacklisted("slave_1")).isFalse();
        assertThat(strategy.isProbing("slave_1")).isFalse();
        assertThat(strategy.getBlacklistedDataSources()).isEmpty();
        assertThat(strategy.filterHealthyNodes(candidates)).contains("slave_1", "slave_2");
    }

    @Test
    @DisplayName("测试连续探活失败时执行指数退避调度且不超过最大间隔上限")
    void testProgressiveBackoffOnConsecutiveFailures() throws Exception {
        DataSource mockDs = mock(DataSource.class);
        when(mockDs.getConnection()).thenThrow(new SQLException("Host unreachable"));

        strategy.setDataSourceLocator(key -> "slave_dead".equals(key) ? mockDs : null);

        // 1. 记录故障
        strategy.recordFailure("slave_dead");
        assertThat(strategy.isProbing("slave_dead")).isTrue();
        assertThat(strategy.getProbeFailureCount("slave_dead")).isZero();
        assertThat(strategy.getProbeCurrentInterval("slave_dead")).isEqualTo(Duration.ofMillis(40));

        // 2. 等待第 1 次探活执行并失败 (40ms + buffer)
        Thread.sleep(70);
        assertThat(strategy.getProbeFailureCount("slave_dead")).isGreaterThanOrEqualTo(1);
        Duration intervalAfterFail1 = strategy.getProbeCurrentInterval("slave_dead");
        assertThat(intervalAfterFail1).isGreaterThanOrEqualTo(Duration.ofMillis(80));

        // 3. 等待后续几次失败，验证退避上限为 probeMaxInterval (200ms)
        Thread.sleep(250);
        assertThat(strategy.getProbeFailureCount("slave_dead")).isGreaterThanOrEqualTo(2);
        Duration intervalAfterMoreFails = strategy.getProbeCurrentInterval("slave_dead");
        assertThat(intervalAfterMoreFails.toMillis()).isLessThanOrEqualTo(200L);
    }

    @Test
    @DisplayName("测试移出黑名单与清理操作安全取消并释放在途探活任务")
    void testUnblacklistAndClearBlacklistCancelsActiveProbes() {
        DataSource mockDs = mock(DataSource.class);
        strategy.setDataSourceLocator(key -> mockDs);

        strategy.recordFailure("slave_1");
        strategy.recordFailure("slave_2");

        assertThat(strategy.isProbing("slave_1")).isTrue();
        assertThat(strategy.isProbing("slave_2")).isTrue();

        // 1. 单节点解除黑名单
        strategy.unblacklist("slave_1");
        assertThat(strategy.isProbing("slave_1")).isFalse();
        assertThat(strategy.isBlacklisted("slave_1")).isFalse();
        assertThat(strategy.isProbing("slave_2")).isTrue();

        // 2. 全量清理黑名单
        strategy.clearBlacklist();
        assertThat(strategy.isProbing("slave_2")).isFalse();
        assertThat(strategy.isBlacklisted("slave_2")).isFalse();
        assertThat(strategy.getBlacklistedDataSources()).isEmpty();
    }

    @Test
    @DisplayName("测试探活目标数据源已被动态移除时自动终止探活并安全移出黑名单")
    void testActiveProbeWhenDataSourceRemovedOrNull() throws Exception {
        Map<String, DataSource> dsMap = new HashMap<>();
        DataSource mockDs = mock(DataSource.class);
        when(mockDs.getConnection()).thenThrow(new SQLException("Dead"));
        dsMap.put("slave_temp", mockDs);

        strategy.setDataSourceLocator(dsMap::get);

        strategy.recordFailure("slave_temp");
        assertThat(strategy.isProbing("slave_temp")).isTrue();

        // 动态注销该数据源
        dsMap.remove("slave_temp");

        // 等待探活触发并检测到数据源不存在
        Thread.sleep(80);

        assertThat(strategy.isProbing("slave_temp")).isFalse();
        assertThat(strategy.isBlacklisted("slave_temp")).isFalse();
    }

    @Test
    @DisplayName("测试 AtengDynamicRoutingDataSource 联动主动探活引擎端到端自愈闭环")
    void testAtengDynamicRoutingDataSourceEndToEndSelfHealing() throws Exception {
        DataSource masterDs = mock(DataSource.class);
        Connection masterConn = mock(Connection.class);
        when(masterDs.getConnection()).thenReturn(masterConn);

        DataSource slaveDs = mock(DataSource.class);
        Connection slaveConn = mock(Connection.class);
        AtomicBoolean slaveAvailable = new AtomicBoolean(false);

        when(slaveDs.getConnection()).thenAnswer(inv -> {
            if (!slaveAvailable.get()) {
                throw new SQLException("Slave down");
            }
            return slaveConn;
        });
        when(slaveConn.isValid(anyInt())).thenAnswer(inv -> slaveAvailable.get());

        AtengDynamicRoutingDataSource routingDs = new AtengDynamicRoutingDataSource(
                Collections.emptyList(),
                properties,
                null,
                strategy,
                null
        );
        routingDs.addDataSource("master", masterDs);
        routingDs.addDataSource("slave", slaveDs);

        DynamicDataSourceContextHolder.push("slave");
        try {
            // 1. 尝试从故障从库获取连接，触发异常并被策略捕获熔断，保底降级获取主库连接
            Connection conn1 = routingDs.getConnection();
            assertThat(conn1).isSameAs(masterConn);

            // 2. 校验从库已被列入黑名单且后台探活已启动
            assertThat(strategy.isBlacklisted("slave")).isTrue();
            assertThat(strategy.isProbing("slave")).isTrue();

            // 3. 模拟从库恢复上线
            slaveAvailable.set(true);

            // 等待后台探活任务自愈 (轮询最多 500ms)
            for (int i = 0; i < 25 && strategy.isBlacklisted("slave"); i++) {
                Thread.sleep(20);
            }

            // 4. 校验从库已从黑名单自愈解脱
            assertThat(strategy.isBlacklisted("slave")).isFalse();
            assertThat(strategy.isProbing("slave")).isFalse();

            // 5. 下一次正常请求成功路由至从库
            Connection conn2 = routingDs.getConnection();
            assertThat(conn2).isSameAs(slaveConn);
        } finally {
            DynamicDataSourceContextHolder.clear();
            routingDs.destroy();
        }
    }

    @Test
    @DisplayName("测试主动探活禁用时降级回退至被动时间窗口冷却判定")
    void testActiveProbeDisabledFallbackToPassiveCooldown() throws InterruptedException {
        properties.getReadWriteSplit().getFailover().setActiveProbeEnabled(false);
        properties.getReadWriteSplit().getFailover().setCooldownWindow(Duration.ofMillis(80));

        HealthAwareDynamicDataSourceStrategy passiveStrategy = new HealthAwareDynamicDataSourceStrategy(properties);
        try {
            passiveStrategy.recordFailure("slave_passive");

            assertThat(passiveStrategy.isBlacklisted("slave_passive")).isTrue();
            assertThat(passiveStrategy.isProbing("slave_passive")).isFalse(); // 未启动主动探活任务

            Thread.sleep(120);

            assertThat(passiveStrategy.isBlacklisted("slave_passive")).isFalse(); // 冷却期结束后自动自愈
        } finally {
            passiveStrategy.destroy();
        }
    }
}
