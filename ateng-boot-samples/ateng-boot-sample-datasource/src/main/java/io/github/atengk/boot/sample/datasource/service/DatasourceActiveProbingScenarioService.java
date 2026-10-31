package io.github.atengk.boot.sample.datasource.service;

import io.github.atengk.boot.datasource.routing.AtengDynamicRoutingDataSource;
import io.github.atengk.boot.datasource.strategy.HealthAwareDynamicDataSourceStrategy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * 从库故障后台主动探活与自愈熔断场景服务
 * <p>
 * 演示从库物理故障发生后自动熔断隔离、后台受管渐进退避探活以及自愈恢复放行流量。
 *
 * @author Ateng
 * @since 2026-10-31
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DatasourceActiveProbingScenarioService {

    private final AtengDynamicRoutingDataSource routingDataSource;

    /**
     * 模拟从库物理节点连接失败并触发熔断黑名单
     *
     * @param replicaKey 从库节点标识
     */
    public void simulateReplicaFailure(String replicaKey) {
        log.warn("[ActiveProbingScenario] 模拟从库节点 [{}] 发生物理异常并记录失败", replicaKey);
        HealthAwareDynamicDataSourceStrategy strategy = routingDataSource.getHealthAwareStrategy();
        if (strategy != null) {
            strategy.recordFailure(replicaKey);
        }
    }

    /**
     * 判断指定从库节点是否处于故障熔断黑名单中
     *
     * @param replicaKey 从库节点标识
     * @return true 表示已被熔断隔离
     */
    public boolean isReplicaBlacklisted(String replicaKey) {
        HealthAwareDynamicDataSourceStrategy strategy = routingDataSource.getHealthAwareStrategy();
        return strategy != null && strategy.isBlacklisted(replicaKey);
    }

    /**
     * 获取当前所有处于黑名单中的从库节点集合
     *
     * @return 黑名单节点集合
     */
    public java.util.Set<String> getBlacklistedDataSources() {
        HealthAwareDynamicDataSourceStrategy strategy = routingDataSource.getHealthAwareStrategy();
        return strategy != null ? strategy.getBlacklistedDataSources() : java.util.Collections.emptySet();
    }

    /**
     * 手动将已修复上线的物理节点移出黑名单（自愈恢复）
     *
     * @param replicaKey 从库节点标识
     */
    public void recoverReplica(String replicaKey) {
        log.info("[ActiveProbingScenario] 手动自愈恢复从库节点: {}", replicaKey);
        HealthAwareDynamicDataSourceStrategy strategy = routingDataSource.getHealthAwareStrategy();
        if (strategy != null) {
            strategy.unblacklist(replicaKey);
        }
    }
}
