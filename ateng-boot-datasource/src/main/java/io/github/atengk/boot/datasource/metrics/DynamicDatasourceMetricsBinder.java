package io.github.atengk.boot.datasource.metrics;

import io.github.atengk.boot.datasource.listener.DynamicDatasourceLifecycleListener;
import io.github.atengk.boot.datasource.model.DataSourcePoolStats;
import io.github.atengk.boot.datasource.template.DynamicDatasourceTemplate;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.util.StringUtils;

import javax.sql.DataSource;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 动态多数据源 Micrometer / Actuator 连接池监控度量绑定器
 * <p>
 * 遵循云原生 Observability 规范与零泄漏不变量，针对动态多数据源及其底层连接池提供指标透视：
 * <ul>
 *     <li><b>活跃连接数</b>：{@value #METRIC_ACTIVE}（当前正被借用执行业务查询的物理连接总数）；</li>
 *     <li><b>空闲连接数</b>：{@value #METRIC_IDLE}（池中就绪可立即借用的物理连接数）；</li>
 *     <li><b>总连接数</b>：{@value #METRIC_TOTAL}（池中持有的全部物理连接总数）；</li>
 *     <li><b>排队线程数</b>：{@value #METRIC_PENDING_THREADS}（等待获取数据库连接的线程数）；</li>
 *     <li><b>维度标签</b>：{@code name=<dsKey>}。</li>
 * </ul>
 * 深度融合数据源全生命周期，在动态添加数据源时自动注册对应 Gauges；在动态摘除数据源时自动注销清理对应 Meters，
 * 彻底杜绝 Prometheus 与 Actuator 指标泄漏与时序爆炸。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-31
 */
public class DynamicDatasourceMetricsBinder implements MeterBinder, DynamicDatasourceLifecycleListener, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(DynamicDatasourceMetricsBinder.class);

    public static final String METRIC_ACTIVE = "datasource.connections.active";
    public static final String METRIC_IDLE = "datasource.connections.idle";
    public static final String METRIC_TOTAL = "datasource.connections.total";
    public static final String METRIC_PENDING_THREADS = "datasource.connections.pending_threads";
    public static final String TAG_NAME = "name";

    private final DynamicDatasourceTemplate template;
    private final ReentrantLock bindLock = new ReentrantLock();
    private final ConcurrentMap<String, DataSourceGaugeTarget> monitoredTargets = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, List<Meter.Id>> registeredMeterIds = new ConcurrentHashMap<>();

    private volatile MeterRegistry registry;

    public DynamicDatasourceMetricsBinder(DynamicDatasourceTemplate template) {
        this.template = Objects.requireNonNull(template, "dynamicDatasourceTemplate 不能为空");
        this.template.addLifecycleListener(this);
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "MeterRegistry 不能为空");
        bindLock.lock();
        try {
            // 为所有已注册的初始物理数据源绑定指标
            Map<String, DataSourcePoolStats> allStats = template.getAllPoolStats();
            for (String dsKey : allStats.keySet()) {
                bindDataSourceInternal(dsKey);
            }
            log.info("ateng-boot-datasource: 成功完成初始动态数据源 Micrometer 度量指标绑定 (数据源数: {})", allStats.size());
        } finally {
            if (bindLock.isHeldByCurrentThread()) {
                bindLock.unlock();
            }
        }
    }

    @Override
    public void onDataSourceAdded(String dsKey, DataSource dataSource) {
        bindDataSource(dsKey, dataSource);
    }

    @Override
    public void onDataSourceRemoved(String dsKey) {
        unbind(dsKey);
    }

    /**
     * 为指定数据源绑定并发布连接池度量指标
     *
     * @param dsKey      数据源唯一标识
     * @param dataSource 物理数据源实例
     */
    public void bindDataSource(String dsKey, DataSource dataSource) {
        if (!StringUtils.hasText(dsKey)) {
            return;
        }
        bindLock.lock();
        try {
            if (registry == null) {
                log.debug("ateng-boot-datasource: MeterRegistry 尚未就绪，暂缓绑定指标 [dsKey={}]", dsKey);
                return;
            }
            // 若之前已绑定该 dsKey，执行先解绑清理再重新绑定（支持热重载）
            if (monitoredTargets.containsKey(dsKey)) {
                unbindInternal(dsKey);
            }
            bindDataSourceInternal(dsKey);
        } finally {
            if (bindLock.isHeldByCurrentThread()) {
                bindLock.unlock();
            }
        }
    }

    /**
     * 注销并清理指定数据源在 Micrometer 中的全部度量指标（防指标泄漏）
     *
     * @param dsKey 数据源唯一标识
     */
    public void unbind(String dsKey) {
        if (!StringUtils.hasText(dsKey)) {
            return;
        }
        bindLock.lock();
        try {
            unbindInternal(dsKey);
        } finally {
            if (bindLock.isHeldByCurrentThread()) {
                bindLock.unlock();
            }
        }
    }

    /**
     * 判断指定数据源是否已被度量监控纳管
     *
     * @param dsKey 数据源唯一标识
     * @return true 表示已被监控
     */
    public boolean isBound(String dsKey) {
        return monitoredTargets.containsKey(dsKey);
    }

    /**
     * 获取当前纳管监控的所有数据源标识集合（只读）
     *
     * @return 数据源标识集合
     */
    public Set<String> getMonitoredKeys() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(monitoredTargets.keySet()));
    }

    @Override
    public void destroy() {
        bindLock.lock();
        try {
            if (template != null) {
                template.removeLifecycleListener(this);
            }
            List<String> keys = new ArrayList<>(monitoredTargets.keySet());
            for (String key : keys) {
                unbindInternal(key);
            }
            log.info("ateng-boot-datasource: DynamicDatasourceMetricsBinder 已安全销毁并释放所有指标");
        } finally {
            if (bindLock.isHeldByCurrentThread()) {
                bindLock.unlock();
            }
        }
    }

    @FunctionalInterface
    private interface MetricValueExtractor {
        double extract(DataSourceGaugeTarget target);
    }

    private record MetricDescriptor(String name, String description, MetricValueExtractor extractor) {}

    private static final List<MetricDescriptor> METRIC_DESCRIPTORS = List.of(
            new MetricDescriptor(METRIC_ACTIVE, "当前物理连接池活跃连接数 (Active Connections)", DataSourceGaugeTarget::getActiveConnections),
            new MetricDescriptor(METRIC_IDLE, "当前物理连接池空闲连接数 (Idle Connections)", DataSourceGaugeTarget::getIdleConnections),
            new MetricDescriptor(METRIC_TOTAL, "当前物理连接池总连接数 (Total Connections)", DataSourceGaugeTarget::getTotalConnections),
            new MetricDescriptor(METRIC_PENDING_THREADS, "当前等待获取连接的排队线程数 (Threads Awaiting Connection)", DataSourceGaugeTarget::getThreadsAwaitingConnection)
    );

    private void bindDataSourceInternal(String dsKey) {
        if (registry == null || !StringUtils.hasText(dsKey)) {
            return;
        }
        DataSourceGaugeTarget target = new DataSourceGaugeTarget(dsKey, template);
        monitoredTargets.put(dsKey, target);

        List<Meter.Id> meterIds = new ArrayList<>(METRIC_DESCRIPTORS.size());
        for (MetricDescriptor descriptor : METRIC_DESCRIPTORS) {
            Meter.Id meterId = Gauge.builder(descriptor.name(), target, descriptor.extractor()::extract)
                    .tag(TAG_NAME, dsKey)
                    .description(descriptor.description())
                    .register(registry)
                    .getId();
            meterIds.add(meterId);
        }

        registeredMeterIds.put(dsKey, meterIds);
        log.info("ateng-boot-datasource: 成功注册数据源 Micrometer 度量指标 [dsKey={}]", dsKey);
    }

    private void unbindInternal(String dsKey) {
        monitoredTargets.remove(dsKey);
        List<Meter.Id> meterIds = registeredMeterIds.remove(dsKey);
        if (registry != null) {
            if (meterIds != null) {
                for (Meter.Id id : meterIds) {
                    registry.remove(id);
                }
            }
            // 防御性二次清理，彻底清理残余 Gauge
            for (MetricDescriptor descriptor : METRIC_DESCRIPTORS) {
                registry.find(descriptor.name()).tag(TAG_NAME, dsKey).gauges().forEach(registry::remove);
            }
        }
        log.info("ateng-boot-datasource: 已注销并清理数据源 Micrometer 度量指标 [dsKey={}]", dsKey);
    }

    /**
     * 强引用度量采样目标包装器（避免 Micrometer 弱引用状态对象被垃圾回收）
     */
    private static class DataSourceGaugeTarget {
        private final String dsKey;
        private final DynamicDatasourceTemplate template;

        DataSourceGaugeTarget(String dsKey, DynamicDatasourceTemplate template) {
            this.dsKey = dsKey;
            this.template = template;
        }

        double getActiveConnections() {
            DataSourcePoolStats stats = template.getPoolStats(dsKey);
            return stats != null ? stats.getActiveConnections() : 0.0;
        }

        double getIdleConnections() {
            DataSourcePoolStats stats = template.getPoolStats(dsKey);
            return stats != null ? stats.getIdleConnections() : 0.0;
        }

        double getTotalConnections() {
            DataSourcePoolStats stats = template.getPoolStats(dsKey);
            return stats != null ? stats.getTotalConnections() : 0.0;
        }

        double getThreadsAwaitingConnection() {
            DataSourcePoolStats stats = template.getPoolStats(dsKey);
            return stats != null ? stats.getThreadsAwaitingConnection() : 0.0;
        }
    }
}
