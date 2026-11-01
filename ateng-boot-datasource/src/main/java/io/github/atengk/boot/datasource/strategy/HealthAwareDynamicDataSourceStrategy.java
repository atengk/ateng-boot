package io.github.atengk.boot.datasource.strategy;

import com.baomidou.dynamic.datasource.strategy.DynamicDataSourceStrategy;
import io.github.atengk.boot.datasource.properties.DatasourceProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import javax.sql.DataSource;
import java.sql.Connection;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * 具备故障感知、主动探活与渐进退避自愈能力的高可用数据源路由策略
 * <p>
 * 1. 当从库节点连接异常时，自动加入熔断黑名单，并将读流量动态重平衡至其余健康从库；<br>
 * 2. 在从库全不可用时，自适应保底降级路由至对应主库；<br>
 * 3. 启用主动探活时，由后台受管调度任务定期执行轻量物理探测（{@code isValid(1)}）；<br>
 * 4. 探活成功即刻自愈放行流量；多次失败则按指数退避递增探活间隔（最高降频至 30s），杜绝高频试错打扰彻底宕机节点。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-24
 */
public class HealthAwareDynamicDataSourceStrategy implements DynamicDataSourceStrategy, DisposableBean, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(HealthAwareDynamicDataSourceStrategy.class);

    private final DatasourceProperties datasourceProperties;
    private final ConcurrentMap<String, Long> blacklist = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, ProbeTaskHolder> activeProbes = new ConcurrentHashMap<>();
    private final AtomicInteger counter = new AtomicInteger(0);
    private final AtomicBoolean closed = new AtomicBoolean(false);

    private final ScheduledExecutorService scheduler;
    private final ThreadPoolTaskScheduler internalScheduler;
    private volatile Function<String, DataSource> dataSourceLocator;

    public HealthAwareDynamicDataSourceStrategy(DatasourceProperties datasourceProperties) {
        this(datasourceProperties, null, null);
    }

    public HealthAwareDynamicDataSourceStrategy(DatasourceProperties datasourceProperties,
                                                ScheduledExecutorService scheduler) {
        this(datasourceProperties, scheduler, null);
    }

    public HealthAwareDynamicDataSourceStrategy(DatasourceProperties datasourceProperties,
                                                ScheduledExecutorService scheduler,
                                                Function<String, DataSource> dataSourceLocator) {
        this.datasourceProperties = datasourceProperties != null ? datasourceProperties : new DatasourceProperties();
        this.dataSourceLocator = dataSourceLocator;
        if (scheduler != null) {
            this.scheduler = scheduler;
            this.internalScheduler = null;
        } else {
            ThreadPoolTaskScheduler taskScheduler = new ThreadPoolTaskScheduler();
            taskScheduler.setPoolSize(1);
            taskScheduler.setThreadNamePrefix("ateng-datasource-probe-");
            taskScheduler.setDaemon(true);
            taskScheduler.setWaitForTasksToCompleteOnShutdown(false);
            taskScheduler.initialize();
            this.internalScheduler = taskScheduler;
            this.scheduler = taskScheduler.getScheduledExecutor();
        }
    }

    @Override
    public String determineKey(List<String> dsNames) {
        if (dsNames == null || dsNames.isEmpty()) {
            return null;
        }

        // 1. 过滤出处于健康状态（未处于熔断黑名单中）的节点
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
     * 过滤指定数据源列表中的健康节点（排除处于黑名单中的节点）
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

        List<String> healthyList = new ArrayList<>();
        for (String dsName : dsNames) {
            if (!isBlacklisted(dsName)) {
                healthyList.add(dsName);
            }
        }
        return healthyList;
    }

    /**
     * 记录数据源连接或执行异常，将其置入熔断黑名单，并在满足条件时触发主动探活
     *
     * @param dsKey 故障数据源 Key
     */
    public void recordFailure(String dsKey) {
        if (dsKey == null || dsKey.isBlank() || closed.get()) {
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

        // 如果启用了后台主动探活且已配置数据源定位器与调度器，启动后台受管主动探活自愈任务
        if (failover.isActiveProbeEnabled() && dataSourceLocator != null && scheduler != null) {
            initiateActiveProbe(dsKey, failover);
        } else {
            log.warn("从库节点 [{}] 发生物理连接异常，已列入熔断黑名单（被动冷却模式），隔离冷却时长: {}ms", dsKey, cooldownMillis);
        }
    }

    /**
     * 启动指定节点的后台主动探活自愈任务
     */
    private void initiateActiveProbe(String dsKey, DatasourceProperties.FailoverProperties failover) {
        activeProbes.computeIfAbsent(dsKey, key -> {
            Duration initialInterval = failover.getProbeInitialInterval() != null
                    ? failover.getProbeInitialInterval()
                    : Duration.ofSeconds(5);
            ProbeTaskHolder holder = new ProbeTaskHolder(key, initialInterval);
            log.warn("从库节点 [{}] 发生物理连接异常，已列入熔断黑名单，启动后台受管主动探活自愈任务 (初始周期: {}ms)",
                    key, initialInterval.toMillis());
            scheduleProbe(holder, initialInterval, failover);
            return holder;
        });
        ProbeTaskHolder scheduled = activeProbes.get(dsKey);
        if (scheduled != null && scheduled.scheduledFuture == null && !closed.get()) {
            activeProbes.remove(dsKey);
        }
    }

    /**
     * 调度指定延迟的探活任务
     */
    private void scheduleProbe(ProbeTaskHolder holder, Duration delay, DatasourceProperties.FailoverProperties failover) {
        if (closed.get()) {
            return;
        }
        try {
            holder.scheduledFuture = scheduler.schedule(() -> executeProbe(holder, failover),
                    delay.toMillis(), TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            log.error("调度从库节点 [{}] 主动探活任务异常，清理探活句柄以防死锁", holder.dsKey, e);
            activeProbes.remove(holder.dsKey);
        }
    }

    /**
     * 执行实际物理连接探活校验与自愈逻辑
     */
    void executeProbe(ProbeTaskHolder holder, DatasourceProperties.FailoverProperties failover) {
        if (closed.get()) {
            return;
        }
        String dsKey = holder.dsKey;
        // 若已不再处于黑名单中，说明已被人工或其它链路移除，终止探活
        if (!blacklist.containsKey(dsKey)) {
            cancelProbe(dsKey);
            return;
        }

        DataSource targetDs = dataSourceLocator != null ? dataSourceLocator.apply(dsKey) : null;
        if (targetDs == null) {
            log.warn("主动探活目标数据源 [{}] 不存在或已注销，终止探活并自动移出黑名单", dsKey);
            unblacklist(dsKey);
            return;
        }

        boolean probeSuccess = false;
        int timeoutSeconds = (int) Math.max(1, failover.getProbeTimeout() != null ? failover.getProbeTimeout().toSeconds() : 1);
        try (Connection conn = targetDs.getConnection()) {
            if (conn != null && conn.isValid(timeoutSeconds)) {
                probeSuccess = true;
            }
        } catch (Throwable e) {
            log.debug("从库节点 [{}] 主动探活物理连接异常: {}", dsKey, e.getMessage());
        }

        if (probeSuccess) {
            log.info("从库节点 [{}] 主动物理连接探活校验成功，自愈恢复放行流量！", dsKey);
            unblacklist(dsKey);
        } else {
            int failures = holder.consecutiveFailures.incrementAndGet();
            Duration nextInterval = calculateBackoffInterval(failures, failover);
            holder.currentInterval = nextInterval;
            log.warn("从库节点 [{}] 主动探活失败 (连续失败 {} 次)，执行渐进退避自愈调度，下一次探活延迟: {}ms",
                    dsKey, failures, nextInterval.toMillis());
            scheduleProbe(holder, nextInterval, failover);
        }
    }

    /**
     * 计算渐进指数退避探活间隔
     *
     * @param failures 连续失败次数
     * @param failover 故障转移配置
     * @return 下一次探活间隔
     */
    Duration calculateBackoffInterval(int failures, DatasourceProperties.FailoverProperties failover) {
        long initialMillis = failover.getProbeInitialInterval() != null
                ? failover.getProbeInitialInterval().toMillis()
                : 5000L;
        long maxMillis = failover.getProbeMaxInterval() != null
                ? failover.getProbeMaxInterval().toMillis()
                : 30000L;

        // 指数退避：initial * 2^failures，防止溢出上限截断为 10 次方
        int shift = Math.min(failures, 10);
        long computedMillis = initialMillis * (1L << shift);
        long effectiveMillis = Math.min(maxMillis, Math.max(initialMillis, computedMillis));
        return Duration.ofMillis(effectiveMillis);
    }

    /**
     * 判断指定数据源是否处于黑名单中
     *
     * @param dsKey 数据源 Key
     * @return true 表示处于黑名单中；false 表示健康
     */
    public boolean isBlacklisted(String dsKey) {
        if (dsKey == null) {
            return false;
        }
        DatasourceProperties.FailoverProperties failover = datasourceProperties.getReadWriteSplit().getFailover();
        if (!failover.isEnabled()) {
            return false;
        }

        // 1. 若处于主动探活中，由主动探活任务控制生命周期，探活成功前保持熔断隔离
        if (failover.isActiveProbeEnabled() && activeProbes.containsKey(dsKey)) {
            return true;
        }

        // 2. 否则按被动模式冷却窗口判定
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
     * 将指定数据源移出熔断黑名单并取消对应的主动探活任务
     *
     * @param dsKey 数据源 Key
     */
    public void unblacklist(String dsKey) {
        if (dsKey == null) {
            return;
        }
        blacklist.remove(dsKey);
        cancelProbe(dsKey);
    }

    /**
     * 取消指定节点的探活任务
     */
    private void cancelProbe(String dsKey) {
        ProbeTaskHolder holder = activeProbes.remove(dsKey);
        if (holder != null) {
            holder.cancel();
        }
    }

    /**
     * 获取当前处于黑名单中的所有数据源 Key（只读）
     */
    public Set<String> getBlacklistedDataSources() {
        long now = System.currentTimeMillis();
        Set<String> activeSet = new HashSet<>(activeProbes.keySet());
        for (Map.Entry<String, Long> entry : blacklist.entrySet()) {
            if (activeProbes.containsKey(entry.getKey())) {
                activeSet.add(entry.getKey());
            } else if (now < entry.getValue()) {
                activeSet.add(entry.getKey());
            } else {
                blacklist.remove(entry.getKey(), entry.getValue());
            }
        }
        return Collections.unmodifiableSet(activeSet);
    }

    /**
     * 清理所有黑名单记录与在途探活任务（供测试或人工运维指令使用）
     */
    public void clearBlacklist() {
        blacklist.clear();
        for (ProbeTaskHolder holder : activeProbes.values()) {
            holder.cancel();
        }
        activeProbes.clear();
    }

    /**
     * 判断指定节点当前是否正在执行主动探活
     *
     * @param dsKey 数据源 Key
     * @return true 表示正在探活
     */
    public boolean isProbing(String dsKey) {
        return activeProbes.containsKey(dsKey);
    }

    /**
     * 获取指定节点当前的探活连续失败次数
     *
     * @param dsKey 数据源 Key
     * @return 连续失败次数
     */
    public int getProbeFailureCount(String dsKey) {
        ProbeTaskHolder holder = activeProbes.get(dsKey);
        return holder != null ? holder.consecutiveFailures.get() : 0;
    }

    /**
     * 获取指定节点当前探活任务的调度间隔
     *
     * @param dsKey 数据源 Key
     * @return 当前调度间隔
     */
    public Duration getProbeCurrentInterval(String dsKey) {
        ProbeTaskHolder holder = activeProbes.get(dsKey);
        return holder != null ? holder.currentInterval : null;
    }

    /**
     * 配置物理数据源查找定位器
     *
     * @param dataSourceLocator 数据源定位函数
     */
    public void setDataSourceLocator(Function<String, DataSource> dataSourceLocator) {
        this.dataSourceLocator = dataSourceLocator;
    }

    /**
     * 获取物理数据源查找定位器
     */
    public Function<String, DataSource> getDataSourceLocator() {
        return this.dataSourceLocator;
    }

    @Override
    public void destroy() {
        if (closed.compareAndSet(false, true)) {
            clearBlacklist();
            if (internalScheduler != null) {
                try {
                    internalScheduler.destroy();
                } catch (Exception e) {
                    log.warn("销毁内部探活调度器异常: {}", e.getMessage());
                }
            }
        }
    }

    @Override
    public void close() {
        destroy();
    }

    /**
     * 主动探活任务持有者内部类
     */
    private static class ProbeTaskHolder {
        final String dsKey;
        final AtomicInteger consecutiveFailures = new AtomicInteger(0);
        volatile ScheduledFuture<?> scheduledFuture;
        volatile Duration currentInterval;

        ProbeTaskHolder(String dsKey, Duration initialInterval) {
            this.dsKey = dsKey;
            this.currentInterval = initialInterval;
        }

        void cancel() {
            ScheduledFuture<?> future = this.scheduledFuture;
            if (future != null && !future.isDone()) {
                future.cancel(true);
            }
        }
    }
}
