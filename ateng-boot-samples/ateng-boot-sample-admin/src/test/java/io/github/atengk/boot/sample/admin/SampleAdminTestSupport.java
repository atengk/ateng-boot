package io.github.atengk.boot.sample.admin;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import de.codecentric.boot.admin.server.domain.entities.Instance;
import de.codecentric.boot.admin.server.domain.entities.InstanceRepository;
import io.github.atengk.boot.admin.notifier.AtengAdminNotificationNotifier;
import io.github.atengk.boot.admin.properties.AtengAdminServerProperties;
import io.github.atengk.boot.sample.admin.config.RecordingNotificationTemplate;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Sample 沙箱测试共享环境清理辅助工具
 * 在用例结束后注销演练实例、重置通知器内存状态（避免写入 60s 发版静默窗跨用例污染）并恢复默认配置基线
 *
 * @author Ateng
 * @since 2026-11-12
 */
final class SampleAdminTestSupport {

    private SampleAdminTestSupport() {
    }

    static void resetSandboxState(InstanceRepository instanceRepository,
                                  AtengAdminNotificationNotifier notificationNotifier,
                                  AtengAdminServerProperties properties,
                                  RecordingNotificationTemplate recordingNotificationTemplate) {
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        ch.qos.logback.classic.Logger logger = context.getLogger("io.github.atengk.boot.sample.admin");
        if (logger != null) {
            logger.setLevel(Level.INFO);
        }
        List<Instance> instances = instanceRepository.findAll().collectList().block();
        if (instances != null) {
            for (Instance instance : instances) {
                if (instance.isRegistered()) {
                    instanceRepository.computeIfPresent(
                            instance.getId(),
                            (id, curr) -> Mono.just(curr.deregister())
                    ).block();
                }
            }
        }
        // 通过 stop + start 清空待决任务、已告警集合与静默缓存，同时保持外部调度器可用且避免写入 60s 发版静默窗
        notificationNotifier.stop();
        notificationNotifier.start();
        if (recordingNotificationTemplate != null) {
            recordingNotificationTemplate.clear();
        }
        if (properties != null) {
            properties.setEnableWriteOperations(true);
            properties.setAllowLocalInstances(true);
            properties.setDebounceDuration(Duration.ofSeconds(30));
            if (properties.getCleanup() != null) {
                properties.getCleanup().setZombieThreshold(Duration.ofHours(24));
            }
            if (properties.getAlert() != null) {
                properties.getAlert().getIncludedServices().clear();
                properties.getAlert().setExcludedServices(new ArrayList<>(List.of("silent-*")));
                properties.getAlert().setNotifyStatuses(new ArrayList<>(List.of("DOWN", "OFFLINE")));
            }
        }
    }
}
