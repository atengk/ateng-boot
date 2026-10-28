package io.github.atengk.boot.datasource.strategy;

import com.baomidou.dynamic.datasource.strategy.DynamicDataSourceStrategy;
import io.github.atengk.boot.datasource.properties.DatasourceProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 具备故障感知与自动黑名单熔断自愈能力的高可用数据源路由策略
 * <p>
 * 当从库节点连接异常时，自动加入滑动黑名单并在冷却窗口（默认 30s）内隔离熔断，
 * 将读流量动态重平衡至其余健康从库；若从库全军覆没，自动保底降级路由至主库。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-24
 */
public class HealthAwareDynamicDataSourceStrategy implements DynamicDataSourceStrategy {

    private static final Logger log = LoggerFactory.getLogger(HealthAwareDynamicDataSourceStrategy.class);

    private final DatasourceProperties datasourceProperties;
    private final ConcurrentMap<String, Long> blacklist = new ConcurrentHashMap<>();
    private final AtomicInteger counter = new AtomicInteger(0);

    public HealthAwareDynamicDataSourceStrategy(DatasourceProperties datasourceProperties) {
        this.datasourceProperties = datasourceProperties != null ? datasourceProperties : new DatasourceProperties();
    }

    @Override
    public String determineKey(List<String> dsNames) {
        if (dsNames == null || dsNames.isEmpty()) {
            return null;
        }

        // 1. 过滤出处于健康状态（未处于熔断黑名单冷却期内）的节点
        List<String> healthyNodes = filterHealthyNodes(dsNames);

        // 2. 存在可用健康节点：基于计数器轮询实现多从库负载均衡
        if (!healthyNodes.isEmpty()) {
            int index = Math.abs(counter.getAndIncrement() % healthyNodes.size());
            return healthyNodes.get(index);
        }

        // 3. 全局告警：候选节点全部处于熔断黑名单中
        log.warn("数据源组内候选节点 {} 全部处于熔断黑名单中！", dsNames);

        // 4. 判断是否配置了主库降级保底
        DatasourceProperties.FailoverProperties failover = datasourceProperties.getReadWriteSplit().getFailover();
        if (failover.isEnabled() && failover.isFallbackToMaster()) {
            String masterDs = datasourceProperties.getReadWriteSplit().getMasterDataSource();
            log.warn("高可用自愈策略生效：从库节点全灭，自动保底降级路由至主库 [{}]", masterDs);
            return masterDs;
        }

        // 若未启用保底，则回退首个节点
        return dsNames.get(0);
    }

    /**
     * 过滤指定数据源列表中的健康节点（排除处于黑名单冷却期内的节点）
     *
     * @param dsNames 待校验数据源列表
     * @return 过滤后的健康数据源列表
     */
    public List<String> filterHealthyNodes(List<String> dsNames) {
        if (dsNames == null || dsNames.isEmpty()) {
            return Collections.emptyList();
        }
        if (!datasourceProperties.getReadWriteSplit().getFailover().isEnabled()) {
            return new ArrayList<>(dsNames);
        }

        long now = System.currentTimeMillis();
        List<String> healthyList = new ArrayList<>();
        for (String dsName : dsNames) {
            Long expireTime = blacklist.get(dsName);
            if (expireTime == null) {
                healthyList.add(dsName);
            } else if (now >= expireTime) {
                // 冷却时间已过，自动自愈移出黑名单
                blacklist.remove(dsName, expireTime);
                log.info("从库节点 [{}] 熔断冷却期已结束，自愈恢复服务", dsName);
                healthyList.add(dsName);
            }
        }
        return healthyList;
    }

    /**
     * 记录数据源连接或执行异常，将其置入熔断黑名单
     *
     * @param dsKey 故障数据源 Key
     */
    public void recordFailure(String dsKey) {
        if (dsKey == null || dsKey.isBlank()) {
            return;
        }
        DatasourceProperties.FailoverProperties failover = datasourceProperties.getReadWriteSplit().getFailover();
        if (!failover.isEnabled()) {
            return;
        }

        long cooldownMillis = failover.getCooldownWindow() != null
                ? failover.getCooldownWindow().toMillis()
                : 30000L;
        long expireTime = System.currentTimeMillis() + cooldownMillis;
        blacklist.put(dsKey, expireTime);
        log.warn("从库节点 [{}] 发生物理连接异常，已列入熔断黑名单，隔离冷却时长: {}ms", dsKey, cooldownMillis);
    }

    /**
     * 判断指定数据源是否处于黑名单冷却期内
     *
     * @param dsKey 数据源 Key
     * @return true 表示处于黑名单中；false 表示健康
     */
    public boolean isBlacklisted(String dsKey) {
        if (dsKey == null || !blacklist.containsKey(dsKey)) {
            return false;
        }
        Long expireTime = blacklist.get(dsKey);
        if (expireTime == null) {
            return false;
        }
        if (System.currentTimeMillis() < expireTime) {
            return true;
        }
        blacklist.remove(dsKey, expireTime);
        return false;
    }

    /**
     * 获取当前处于黑名单中的所有数据源 Key（只读）
     */
    public Set<String> getBlacklistedDataSources() {
        long now = System.currentTimeMillis();
        Set<String> activeBlacklist = new HashSet<>();
        for (Map.Entry<String, Long> entry : blacklist.entrySet()) {
            if (now < entry.getValue()) {
                activeBlacklist.add(entry.getKey());
            } else {
                blacklist.remove(entry.getKey(), entry.getValue());
            }
        }
        return Collections.unmodifiableSet(activeBlacklist);
    }

    /**
     * 清理所有黑名单记录（供测试或人工运维指令使用）
     */
    public void clearBlacklist() {
        blacklist.clear();
    }
}
