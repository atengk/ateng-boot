package io.github.atengk.boot.datasource.strategy;

import io.github.atengk.boot.datasource.properties.DatasourceProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 高可用故障感知与黑名单熔断自愈策略 (HealthAwareDynamicDataSourceStrategy) 单元测试
 *
 * @author Ateng
 * @since 2026-10-24
 */
class HealthAwareDynamicDataSourceStrategyTest {

    private DatasourceProperties properties;
    private HealthAwareDynamicDataSourceStrategy strategy;

    @BeforeEach
    void setUp() {
        properties = new DatasourceProperties();
        properties.getReadWriteSplit().setMasterDataSource("master");
        properties.getReadWriteSplit().getFailover().setEnabled(true);
        properties.getReadWriteSplit().getFailover().setCooldownWindow(Duration.ofMillis(300));
        properties.getReadWriteSplit().getFailover().setFallbackToMaster(true);

        strategy = new HealthAwareDynamicDataSourceStrategy(properties);
    }

    @AfterEach
    void tearDown() {
        strategy.clearBlacklist();
    }

    @Test
    @DisplayName("测试全部从库节点健康时在候选节点中正常轮询路由")
    void testHealthyNodesRouting() {
        List<String> candidates = List.of("slave_1", "slave_2");

        String node1 = strategy.determineKey(candidates);
        String node2 = strategy.determineKey(candidates);

        assertThat(candidates).contains(node1, node2);
    }

    @Test
    @DisplayName("测试单节点故障时自动加入黑名单并在剩余健康节点中自愈调度")
    void testSingleNodeFailureBlacklisting() {
        List<String> candidates = List.of("slave_1", "slave_2");

        // 记录 slave_1 故障
        strategy.recordFailure("slave_1");

        assertThat(strategy.isBlacklisted("slave_1")).isTrue();
        assertThat(strategy.isBlacklisted("slave_2")).isFalse();
        assertThat(strategy.getBlacklistedDataSources()).containsExactly("slave_1");

        // 此时路由应当仅选择健康的 slave_2
        for (int i = 0; i < 5; i++) {
            assertThat(strategy.determineKey(candidates)).isEqualTo("slave_2");
        }
    }

    @Test
    @DisplayName("测试全部从库节点全灭时自动保底降级路由至 master 主库")
    void testAllNodesFailureFallbackToMaster() {
        List<String> candidates = List.of("slave_1", "slave_2");

        // 记录全部从库节点故障
        strategy.recordFailure("slave_1");
        strategy.recordFailure("slave_2");

        assertThat(strategy.filterHealthyNodes(candidates)).isEmpty();

        // 熔断保底自动路由至 master
        String fallback = strategy.determineKey(candidates);
        assertThat(fallback).isEqualTo("master");
    }

    @Test
    @DisplayName("测试冷却期过后黑名单节点自动自愈恢复服务")
    void testCooldownAutoRecovery() throws InterruptedException {
        List<String> candidates = List.of("slave_1");

        strategy.recordFailure("slave_1");
        assertThat(strategy.isBlacklisted("slave_1")).isTrue();

        // 等待冷却期超时 (300ms)
        Thread.sleep(350);

        assertThat(strategy.isBlacklisted("slave_1")).isFalse();
        assertThat(strategy.determineKey(candidates)).isEqualTo("slave_1");
    }
}
