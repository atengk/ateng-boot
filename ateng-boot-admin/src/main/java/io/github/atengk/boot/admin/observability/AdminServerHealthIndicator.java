package io.github.atengk.boot.admin.observability;

import de.codecentric.boot.admin.server.domain.entities.Instance;
import de.codecentric.boot.admin.server.domain.entities.InstanceRepository;
import io.github.atengk.boot.admin.notifier.AtengAdminNotificationNotifier;
import io.github.atengk.boot.admin.properties.AtengAdminServerProperties;
import io.github.atengk.boot.admin.scheduler.ZombieInstanceCleanupScheduler;
import io.github.atengk.boot.admin.silence.AdminSilenceManager;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;

import java.time.Duration;
import java.util.Collections;
import java.util.List;

/**
 * Spring Boot Admin 监控中心自身运行健康检查探针
 * <p>
 * 向 Actuator {@code /actuator/health} 汇总汇报受管实例注册表状态摘要、
 * {@link AtengAdminNotificationNotifier} 调度状态、{@link ZombieInstanceCleanupScheduler} 运行状态及动态静默规则数；
 * 若实例仓库不可用、通知调度器意外关闭或已启用的清理调度器停机，立即准确汇报 {@code DOWN}。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-21
 */
public class AdminServerHealthIndicator implements HealthIndicator {

    private static final Duration REPO_TIMEOUT = Duration.ofSeconds(1);

    private final InstanceRepository repository;
    private final AtengAdminNotificationNotifier notifier;
    private final ZombieInstanceCleanupScheduler cleanupScheduler;
    private final AdminSilenceManager silenceManager;
    private final AtengAdminServerProperties properties;

    public AdminServerHealthIndicator(InstanceRepository repository,
                                      AtengAdminNotificationNotifier notifier,
                                      ZombieInstanceCleanupScheduler cleanupScheduler,
                                      AdminSilenceManager silenceManager,
                                      AtengAdminServerProperties properties) {
        this.repository = repository;
        this.notifier = notifier;
        this.cleanupScheduler = cleanupScheduler;
        this.silenceManager = silenceManager;
        this.properties = properties;
    }

    @Override
    public Health health() {
        List<Instance> registeredInstances = Collections.emptyList();
        if (repository != null) {
            try {
                List<Instance> all = repository.findAll().collectList().block(REPO_TIMEOUT);
                if (all != null) {
                    registeredInstances = all.stream().filter(Instance::isRegistered).toList();
                }
            } catch (Exception ex) {
                return Health.down(ex)
                        .withDetail("reason", "InstanceRepository read failed: " + ex.getMessage())
                        .build();
            }
        }

        int totalRegistered = registeredInstances.size();
        int upCount = 0;
        for (Instance instance : registeredInstances) {
            String status = instance.getStatusInfo() != null ? instance.getStatusInfo().getStatus() : "UNKNOWN";
            if ("UP".equalsIgnoreCase(status)) {
                upCount++;
            }
        }
        int nonUpCount = totalRegistered - upCount;

        boolean notifierHealthy = notifier == null || !notifier.isSchedulerShutdown();
        boolean notifierRunning = notifier != null && notifier.isRunning();
        int pendingAlerts = notifier != null ? notifier.getPendingTasks().size() : 0;
        int alertedInstances = notifier != null ? notifier.getAlertedInstances().size() : 0;

        boolean cleanupEnabled = properties != null && properties.getCleanup() != null && properties.getCleanup().isEnabled();
        boolean cleanupRunning = cleanupScheduler != null && cleanupScheduler.isRunning();
        boolean cleanupHealthy = !cleanupEnabled || cleanupRunning;

        int activeSilences = silenceManager != null ? silenceManager.listActiveSilences().size() : 0;

        Health.Builder builder = (notifierHealthy && cleanupHealthy) ? Health.up() : Health.down();
        if (!notifierHealthy) {
            builder.withDetail("reason", "Notification scheduler is shutdown");
        } else if (!cleanupHealthy) {
            builder.withDetail("reason", "Cleanup scheduler is enabled but not running");
        }

        return builder
                .withDetail("registeredInstances", totalRegistered)
                .withDetail("upInstances", upCount)
                .withDetail("nonUpInstances", nonUpCount)
                .withDetail("notifierRunning", notifierRunning)
                .withDetail("pendingAlerts", pendingAlerts)
                .withDetail("alertedInstances", alertedInstances)
                .withDetail("cleanupEnabled", cleanupEnabled)
                .withDetail("cleanupRunning", cleanupRunning)
                .withDetail("activeSilences", activeSilences)
                .build();
    }
}
