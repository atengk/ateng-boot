package io.github.atengk.boot.observability.health;

import io.github.atengk.boot.observability.properties.ObservabilityProperties;
import io.github.atengk.boot.storage.driver.StorageDriver;
import io.github.atengk.boot.storage.factory.StorageDriverFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.health.Health;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.regex.Pattern;

/**
 * 对象存储安全健康探针
 * <p>
 * 周期性探查本地目录物理可写性（{@code Files.isWritable}）与 S3 存储桶网络连通性，
 * 继承 {@link AbstractSafeHealthIndicator} 实施异步超时切断与状态降级防卫，并在异常时安全脱敏敏感凭据。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-22
 */
@Slf4j
public class StorageHealthIndicator extends AbstractSafeHealthIndicator {

    private static final Pattern SENSITIVE_PATTERN = Pattern.compile("(?i)(accessKey|secretKey|password|token|secret)[=:][^,&\\s]+");

    private final ObjectProvider<StorageDriverFactory> driverFactoryProvider;
    private final ObjectProvider<StorageDriver> defaultDriverProvider;

    public StorageHealthIndicator(
            ObjectProvider<StorageDriverFactory> driverFactoryProvider,
            ObjectProvider<StorageDriver> defaultDriverProvider) {
        this(driverFactoryProvider, defaultDriverProvider, (Duration) null, null);
    }

    public StorageHealthIndicator(
            ObjectProvider<StorageDriverFactory> driverFactoryProvider,
            ObjectProvider<StorageDriver> defaultDriverProvider,
            ObservabilityProperties properties) {
        this(driverFactoryProvider, defaultDriverProvider, properties, null);
    }

    public StorageHealthIndicator(
            ObjectProvider<StorageDriverFactory> driverFactoryProvider,
            ObjectProvider<StorageDriver> defaultDriverProvider,
            ObservabilityProperties properties,
            Executor executor) {
        super(properties, executor);
        this.driverFactoryProvider = driverFactoryProvider;
        this.defaultDriverProvider = defaultDriverProvider;
    }

    public StorageHealthIndicator(
            ObjectProvider<StorageDriverFactory> driverFactoryProvider,
            ObjectProvider<StorageDriver> defaultDriverProvider,
            Duration timeout,
            Executor executor) {
        super(timeout, executor);
        this.driverFactoryProvider = driverFactoryProvider;
        this.defaultDriverProvider = defaultDriverProvider;
    }

    @Override
    protected void doCheck(Health.Builder builder) throws Exception {
        StorageDriverFactory factory = driverFactoryProvider != null ? driverFactoryProvider.getIfAvailable() : null;
        if (factory != null && !factory.getAllDrivers().isEmpty()) {
            boolean allHealthy = true;
            Map<String, Object> sourcesDetail = new LinkedHashMap<>();

            for (Map.Entry<String, StorageDriver> entry : factory.getAllDrivers().entrySet()) {
                String sourceName = entry.getKey();
                StorageDriver driver = entry.getValue();
                Map<String, Object> info = probeDriver(driver);
                sourcesDetail.put(sourceName, info);
                if (!"UP".equals(info.get("status"))) {
                    allHealthy = false;
                }
            }

            if (allHealthy) {
                builder.up().withDetails(sourcesDetail);
            } else {
                builder.down().withDetails(sourcesDetail);
            }
            return;
        }

        StorageDriver defaultDriver = defaultDriverProvider != null ? defaultDriverProvider.getIfAvailable() : null;
        if (defaultDriver != null) {
            Map<String, Object> info = probeDriver(defaultDriver);
            boolean isUp = "UP".equals(info.get("status"));
            if (isUp) {
                builder.up().withDetails(info);
            } else {
                builder.down().withDetails(info);
            }
            return;
        }

        builder.unknown().withDetail("message", "未检测到已装配的存储驱动");
    }

    /**
     * 单个驱动实例探查
     */
    private Map<String, Object> probeDriver(StorageDriver driver) {
        Map<String, Object> details = new LinkedHashMap<>();
        if (driver == null) {
            details.put("status", "DOWN");
            details.put("error", "驱动实例为 null");
            return details;
        }

        details.put("driver", driver.getClass().getSimpleName());
        Map<String, Object> diag = driver.getDiagnostics();
        if (diag != null && !diag.isEmpty()) {
            details.putAll(diag);
        }

        try {
            driver.ping();
            details.put("status", "UP");
        } catch (Throwable t) {
            details.put("status", "DOWN");
            details.put("error", sanitizeMessage(t.getMessage()));
        }
        return details;
    }

    /**
     * 脱敏异常信息中的敏感凭据
     */
    private String sanitizeMessage(String message) {
        if (message == null || message.isBlank()) {
            return "存储驱动探测异常";
        }
        return SENSITIVE_PATTERN.matcher(message).replaceAll("$1=******");
    }
}
