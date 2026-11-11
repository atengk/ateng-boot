package io.github.atengk.boot.sample.admin.controller;

import de.codecentric.boot.admin.server.domain.entities.Instance;
import de.codecentric.boot.admin.server.domain.entities.InstanceRepository;
import de.codecentric.boot.admin.server.domain.events.InstanceDeregisteredEvent;
import de.codecentric.boot.admin.server.domain.events.InstanceStatusChangedEvent;
import de.codecentric.boot.admin.server.domain.values.InstanceId;
import de.codecentric.boot.admin.server.domain.values.StatusInfo;
import io.github.atengk.boot.admin.notifier.AtengAdminNotificationNotifier;
import io.github.atengk.boot.admin.properties.AtengAdminServerProperties;
import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.core.util.BusinessAssert;
import io.github.atengk.boot.sample.admin.config.RecordingNotificationTemplate;
import io.github.atengk.boot.sample.admin.dto.AlertStateSnapshotVO;
import io.github.atengk.boot.sample.admin.dto.GracefulDeregisterRequest;
import io.github.atengk.boot.sample.admin.dto.RecordedAlertCardVO;
import io.github.atengk.boot.sample.admin.dto.StatusTransitionTriggerRequest;
import io.github.atengk.boot.sample.admin.support.SampleStatusConverter;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 告警防抖状态机、K8s 发版静默与离线卡片回溯领域控制器
 * 遵循被动监听能力的“手动触发端 + 状态运维查询端”双端暴露规范
 *
 * @author Ateng
 * @since 2026-11-11
 */
@Slf4j
@RestController
@RequestMapping("/sample/admin/alert")
public class AdminAlertController {

    private final InstanceRepository instanceRepository;
    private final AtengAdminNotificationNotifier notifier;
    private final AtengAdminServerProperties properties;
    private final ObjectProvider<RecordingNotificationTemplate> recordingTemplateProvider;
    private final ReentrantLock debounceOverrideLock = new ReentrantLock();

    public AdminAlertController(InstanceRepository instanceRepository,
                                AtengAdminNotificationNotifier notifier,
                                AtengAdminServerProperties properties,
                                ObjectProvider<RecordingNotificationTemplate> recordingTemplateProvider) {
        this.instanceRepository = instanceRepository;
        this.notifier = notifier;
        this.properties = properties;
        this.recordingTemplateProvider = recordingTemplateProvider;
    }

    /**
     * 模拟触发实例状态跃迁事件（驱动 Fault Debounce Window 与 Alert Rule Filtering 状态机）
     *
     * @param request 状态跃迁触发参数
     * @return 触发后的告警状态机运维快照
     */
    @PostMapping("/status-transition")
    public Result<AlertStateSnapshotVO> triggerStatusTransition(@Valid @RequestBody StatusTransitionTriggerRequest request) {
        InstanceId instanceId = InstanceId.of(request.getInstanceId().trim());
        StatusInfo statusInfo = buildStatusInfo(request);

        Instance updated = instanceRepository.compute(instanceId, (id, current) -> {
            if (current == null) {
                return Mono.empty();
            }
            return Mono.just(current.withStatusInfo(statusInfo));
        }).block();
        BusinessAssert.notNull(updated, "未找到目标实例，请先登记演练实例: " + instanceId.getValue());

        InstanceStatusChangedEvent event = new InstanceStatusChangedEvent(
                instanceId,
                updated.getVersion(),
                statusInfo
        );

        boolean immediate = Boolean.TRUE.equals(request.getImmediate());
        if (immediate) {
            // 仅在单机沙箱即时演示模式下受锁串行切换 0s 防抖窗口并等待派发完成
            debounceOverrideLock.lock();
            Duration originalDebounce = properties.getDebounceDuration();
            try {
                properties.setDebounceDuration(Duration.ZERO);
                notifier.notify(event).block();
                awaitPendingTaskIfPresent(instanceId);
            } finally {
                properties.setDebounceDuration(originalDebounce);
                debounceOverrideLock.unlock();
            }
        } else {
            notifier.notify(event).block();
        }

        return Result.success(buildStateSnapshot());
    }

    /**
     * 触发 Kubernetes 优雅下线注销事件，演练 Graceful Deregistration Silence（60s 发版静默期）
     *
     * @param request 优雅下线注销请求参数
     * @return 触发后的告警状态机运维快照
     */
    @PostMapping("/graceful-deregister")
    public Result<AlertStateSnapshotVO> triggerGracefulDeregister(@Valid @RequestBody GracefulDeregisterRequest request) {
        InstanceId instanceId = InstanceId.of(request.getInstanceId().trim());
        Instance existing = instanceRepository.find(instanceId).block();
        BusinessAssert.notNull(existing, "未找到目标实例，请先登记演练实例: " + instanceId.getValue());

        long baseVersion = existing.getVersion();
        notifier.notify(new InstanceDeregisteredEvent(instanceId, baseVersion + 1)).block();

        if (StringUtils.hasText(request.getFollowUpFaultStatus())) {
            StatusInfo followUp = SampleStatusConverter.toStatusInfo(request.getFollowUpFaultStatus());
            notifier.notify(new InstanceStatusChangedEvent(instanceId, baseVersion + 2, followUp)).block();
        }

        return Result.success(buildStateSnapshot());
    }

    /**
     * 查询当前防抖待决队列、已告警实例、发版静默实例与名单过滤配置快照
     *
     * @return 告警状态机运维快照
     */
    @GetMapping("/state")
    public Result<AlertStateSnapshotVO> getAlertState() {
        return Result.success(buildStateSnapshot());
    }

    /**
     * 查询离线内存录制桩捕获的脱敏富文本告警卡片列表（非 local Profile 下可用）
     *
     * @return 已录制告警卡片只读列表
     */
    @GetMapping("/cards")
    public Result<List<RecordedAlertCardVO>> listRecordedCards() {
        RecordingNotificationTemplate recorder = recordingTemplateProvider.getIfAvailable();
        if (recorder == null) {
            return Result.success(Collections.emptyList());
        }
        return Result.success(recorder.getRecordedCards());
    }

    private void awaitPendingTaskIfPresent(InstanceId instanceId) {
        ScheduledFuture<?> future = notifier.getPendingTasks().get(instanceId);
        if (future == null) {
            return;
        }
        try {
            future.get(2, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            log.warn("等待即时告警调度任务被中断: instanceId={}", instanceId.getValue(), ex);
        } catch (Exception ex) {
            log.warn("等待即时告警调度任务未正常完成: instanceId={}, reason={}", instanceId.getValue(), ex.getMessage());
        }
    }

    private StatusInfo buildStatusInfo(StatusTransitionTriggerRequest request) {
        String normalized = request.getStatus().trim().toUpperCase(Locale.ROOT);
        if ("DOWN".equals(normalized) && (StringUtils.hasText(request.getProbeComponent()) || StringUtils.hasText(request.getProbeError()))) {
            String component = StringUtils.hasText(request.getProbeComponent()) ? request.getProbeComponent().trim() : "dependency";
            String error = StringUtils.hasText(request.getProbeError()) ? request.getProbeError().trim() : "Health probe failed";
            return StatusInfo.valueOf("DOWN", Map.of(component, Map.of("status", "DOWN", "error", error)));
        }
        return SampleStatusConverter.toStatusInfo(normalized);
    }

    private AlertStateSnapshotVO buildStateSnapshot() {
        List<String> pendingIds = notifier.getPendingTasks().entrySet().stream()
                .filter(entry -> entry.getValue() != null && !entry.getValue().isDone())
                .map(entry -> entry.getKey().getValue())
                .sorted()
                .toList();
        List<String> alertedIds = notifier.getAlertedInstances().stream()
                .map(InstanceId::getValue)
                .sorted()
                .toList();
        List<String> silentIds = notifier.getDeregisteredInstances().keySet().stream()
                .map(InstanceId::getValue)
                .sorted()
                .toList();

        AtengAdminServerProperties.AlertProperties alertProps = properties.getAlert();
        return AlertStateSnapshotVO.builder()
                .debounceDuration(properties.getDebounceDuration())
                .pendingCount(pendingIds.size())
                .pendingInstanceIds(pendingIds)
                .alertedInstanceIds(alertedIds)
                .deregisteredSilentInstanceIds(silentIds)
                .includedServices(alertProps != null ? List.copyOf(alertProps.getIncludedServices()) : Collections.emptyList())
                .excludedServices(alertProps != null ? List.copyOf(alertProps.getExcludedServices()) : Collections.emptyList())
                .notifyStatuses(alertProps != null ? List.copyOf(alertProps.getNotifyStatuses()) : Collections.emptyList())
                .build();
    }
}
