package io.github.atengk.boot.storage.health;

import io.github.atengk.boot.storage.driver.LocalDriver;
import io.github.atengk.boot.storage.driver.S3Driver;
import io.github.atengk.boot.storage.driver.StorageDriver;
import io.github.atengk.boot.storage.factory.StorageDriverFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Spring Boot Actuator 存储健康探针组件
 * 周期性探查本地目录物理可读写性与 S3 存储桶网络连通性，异常时安全脱敏返回故障原因，无缝打通 Kubernetes 探针与微服务监控
 *
 * @author Ateng
 * @since 2026-10-20
 */
public class StorageHealthIndicator implements HealthIndicator {

    private static final Pattern SENSITIVE_PATTERN = Pattern.compile("(?i)(accessKey|secretKey|password|token|secret)[=:][^,&\\s]+");

    private final ObjectProvider<StorageDriverFactory> driverFactoryProvider;
    private final ObjectProvider<StorageDriver> defaultDriverProvider;

    public StorageHealthIndicator(
            ObjectProvider<StorageDriverFactory> driverFactoryProvider,
            ObjectProvider<StorageDriver> defaultDriverProvider) {
        this.driverFactoryProvider = driverFactoryProvider;
        this.defaultDriverProvider = defaultDriverProvider;
    }

    @Override
    public Health health() {
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

            Health.Builder builder = allHealthy ? Health.up() : Health.down();
            return builder.withDetails(sourcesDetail).build();
        }

        StorageDriver defaultDriver = defaultDriverProvider != null ? defaultDriverProvider.getIfAvailable() : null;
        if (defaultDriver != null) {
            Map<String, Object> info = probeDriver(defaultDriver);
            boolean isUp = "UP".equals(info.get("status"));
            Health.Builder builder = isUp ? Health.up() : Health.down();
            return builder.withDetails(info).build();
        }

        return Health.unknown().withDetail("message", "未检测到已装配的存储驱动").build();
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
