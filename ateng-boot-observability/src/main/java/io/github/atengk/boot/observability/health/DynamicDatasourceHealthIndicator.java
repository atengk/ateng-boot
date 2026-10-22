package io.github.atengk.boot.observability.health;

import io.github.atengk.boot.mybatisplus.guard.DynamicDatasourceGuard;
import io.github.atengk.boot.observability.properties.ObservabilityProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.actuate.health.Health;
import org.springframework.context.ApplicationContext;

import javax.sql.DataSource;
import java.sql.Connection;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executor;

/**
 * 动态多数据源与连接池安全健康探针组件
 * <p>
 * 周期性探查 Spring 容器中所有数据源与动态路由数据源的物理连通性与连接池可用状态，
 * 继承 {@link AbstractSafeHealthIndicator} 实施异步超时切断与状态降级防卫。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-22
 */
@Slf4j
public class DynamicDatasourceHealthIndicator extends AbstractSafeHealthIndicator {

    private final ApplicationContext applicationContext;

    public DynamicDatasourceHealthIndicator(ApplicationContext applicationContext) {
        this(applicationContext, (Duration) null, null);
    }

    public DynamicDatasourceHealthIndicator(ApplicationContext applicationContext, ObservabilityProperties properties) {
        this(applicationContext, properties, null);
    }

    public DynamicDatasourceHealthIndicator(ApplicationContext applicationContext, ObservabilityProperties properties, Executor executor) {
        super(properties, executor);
        this.applicationContext = applicationContext;
    }

    public DynamicDatasourceHealthIndicator(ApplicationContext applicationContext, Duration timeout, Executor executor) {
        super(timeout, executor);
        this.applicationContext = applicationContext;
    }

    @Override
    protected void doCheck(Health.Builder builder) throws Exception {
        if (applicationContext == null) {
            builder.unknown().withDetail("message", "ApplicationContext 为 null，跳过多数据源健康探测");
            return;
        }

        Map<String, DataSource> dataSourceBeans = applicationContext.getBeansOfType(DataSource.class);
        if (dataSourceBeans.isEmpty()) {
            builder.unknown().withDetail("message", "未检测到已装配的 DataSource 实例");
            return;
        }

        Map<String, Object> details = new LinkedHashMap<>();
        boolean allHealthy = true;

        // 探测 DynamicDatasourceGuard 是否检测到多数据源或上下文污染
        boolean multiDetected = DynamicDatasourceGuard.isMultiDataSourceDetected() || dataSourceBeans.size() > 1;
        details.put("multiDataSourceDetected", multiDetected);
        details.put("dataSourceCount", dataSourceBeans.size());

        Map<String, Object> poolsDetail = new LinkedHashMap<>();

        for (Map.Entry<String, DataSource> entry : dataSourceBeans.entrySet()) {
            String beanName = entry.getKey();
            DataSource ds = entry.getValue();
            Map<String, Object> dsInfo = probeDataSource(beanName, ds);
            poolsDetail.put(beanName, dsInfo);
            if (!"UP".equals(dsInfo.get("status"))) {
                allHealthy = false;
            }
        }

        details.put("dataSources", poolsDetail);

        if (allHealthy) {
            builder.up().withDetails(details);
        } else {
            builder.down().withDetails(details);
        }
    }

    /**
     * 探测单个数据源连接池可用性
     */
    private Map<String, Object> probeDataSource(String name, DataSource ds) {
        Map<String, Object> info = new LinkedHashMap<>();
        if (ds == null) {
            info.put("status", "DOWN");
            info.put("error", "DataSource 实例为 null");
            return info;
        }

        info.put("type", ds.getClass().getSimpleName());

        // 优先检查是否为 DynamicRoutingDataSource (com.baomidou.dynamic.datasource)
        if (ds.getClass().getName().contains("DynamicRoutingDataSource")) {
            return probeDynamicRoutingDataSource(name, ds, info);
        }

        // 标准 JDBC 数据源探测
        try (Connection conn = ds.getConnection()) {
            boolean valid = conn.isValid(1); // 1s 超时校验
            if (valid) {
                info.put("status", "UP");
                info.put("databaseProductName", conn.getMetaData().getDatabaseProductName());
                info.put("databaseProductVersion", conn.getMetaData().getDatabaseProductVersion());
            } else {
                info.put("status", "DOWN");
                info.put("error", "Connection.isValid(1) 返回 false");
            }
        } catch (Throwable t) {
            info.put("status", "DOWN");
            info.put("error", t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName());
        }

        return info;
    }

    private Map<String, Object> probeDynamicRoutingDataSource(String name, DataSource ds, Map<String, Object> info) {
        try {
            // 反射获取 getDataSources()
            java.lang.reflect.Method getDataSourcesMethod = ds.getClass().getMethod("getDataSources");
            @SuppressWarnings("unchecked")
            Map<String, DataSource> subDataSources = (Map<String, DataSource>) getDataSourcesMethod.invoke(ds);
            if (subDataSources != null && !subDataSources.isEmpty()) {
                Map<String, Object> subDetails = new LinkedHashMap<>();
                boolean allSubHealthy = true;
                for (Map.Entry<String, DataSource> subEntry : subDataSources.entrySet()) {
                    Map<String, Object> subInfo = new LinkedHashMap<>();
                    try (Connection conn = subEntry.getValue().getConnection()) {
                        boolean valid = conn.isValid(1);
                        subInfo.put("status", valid ? "UP" : "DOWN");
                        if (!valid) {
                            allSubHealthy = false;
                        }
                    } catch (Throwable t) {
                        subInfo.put("status", "DOWN");
                        subInfo.put("error", t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName());
                        allSubHealthy = false;
                    }
                    subDetails.put(subEntry.getKey(), subInfo);
                }
                info.put("status", allSubHealthy ? "UP" : "DOWN");
                info.put("routingDataSources", subDetails);
                return info;
            }
        } catch (Throwable ignored) {
            // fallback to standard probe
        }
        // 如果反射探测失败，尝试直接获取连接
        try (Connection conn = ds.getConnection()) {
            boolean valid = conn.isValid(1);
            info.put("status", valid ? "UP" : "DOWN");
        } catch (Throwable t) {
            info.put("status", "DOWN");
            info.put("error", t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName());
        }
        return info;
    }
}
