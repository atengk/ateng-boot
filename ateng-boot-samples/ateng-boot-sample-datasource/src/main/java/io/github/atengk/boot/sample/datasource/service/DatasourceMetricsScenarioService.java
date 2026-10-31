package io.github.atengk.boot.sample.datasource.service;

import io.github.atengk.boot.datasource.metrics.DynamicDatasourceMetricsBinder;
import io.github.atengk.boot.datasource.model.DataSourcePoolStats;
import io.github.atengk.boot.datasource.template.DynamicDatasourceTemplate;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * 动态多数据源运行指标快照与 Micrometer 度量场景服务
 * <p>
 * 演示 DataSourcePoolStats 连接池实时快照提取与 Micrometer Gauges 监控采样观测。
 *
 * @author Ateng
 * @since 2026-10-31
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DatasourceMetricsScenarioService {

    private final DynamicDatasourceTemplate dynamicDatasourceTemplate;
    private final ObjectProvider<MeterRegistry> meterRegistryProvider;
    private final ObjectProvider<DynamicDatasourceMetricsBinder> metricsBinderProvider;

    /**
     * 查询指定数据源连接池的实时运行指标快照 DTO
     *
     * @param dsKey 数据源键名
     * @return 运行指标快照
     */
    public DataSourcePoolStats getPoolStats(String dsKey) {
        log.info("[MetricsScenario] 查询数据源 [{}] 连接池实时状态快照", dsKey);
        return dynamicDatasourceTemplate.getPoolStats(dsKey);
    }

    /**
     * 查询所有已注册物理数据源的连接池实时指标映射表
     *
     * @return 运行指标映射集合
     */
    public Map<String, DataSourcePoolStats> getAllPoolStats() {
        return dynamicDatasourceTemplate.getAllPoolStats();
    }

    /**
     * 从 Micrometer Registry 中读取指定数据源的 Gauge 采样值
     *
     * @param metricName 指标名（如 datasource.connections.active）
     * @param dsKey      数据源键名
     * @return 当前采样值；若未找到返回 null
     */
    public Double getMicrometerGaugeValue(String metricName, String dsKey) {
        MeterRegistry registry = meterRegistryProvider.getIfAvailable();
        if (registry == null) {
            log.warn("[MetricsScenario] 当前环境未注入 MeterRegistry");
            return null;
        }
        Gauge gauge = registry.find(metricName).tag("name", dsKey).gauge();
        return gauge != null ? gauge.value() : null;
    }

    /**
     * 判断 DynamicDatasourceMetricsBinder 是否已处于就绪运行状态
     *
     * @return true 表示已装配
     */
    public boolean isMetricsBinderActive() {
        return metricsBinderProvider.getIfAvailable() != null;
    }
}
