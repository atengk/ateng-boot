package io.github.atengk.boot.admin.notifier;

import de.codecentric.boot.admin.server.domain.entities.Instance;
import de.codecentric.boot.admin.server.domain.entities.InstanceRepository;
import de.codecentric.boot.admin.server.domain.events.InstanceDeregisteredEvent;
import de.codecentric.boot.admin.server.domain.events.InstanceEvent;
import de.codecentric.boot.admin.server.domain.events.InstanceStatusChangedEvent;
import de.codecentric.boot.admin.server.domain.values.InstanceId;
import de.codecentric.boot.admin.server.domain.values.StatusInfo;
import de.codecentric.boot.admin.server.notify.AbstractStatusChangeNotifier;
import io.github.atengk.boot.admin.properties.AtengAdminServerProperties;
import io.github.atengk.boot.notification.model.CardMessage;
import io.github.atengk.boot.notification.template.NotificationTemplate;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.util.PatternMatchUtils;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 状态变更防抖与多端机器人卡片告警通知器
 * 严格遵循确定性生命周期治理（SmartLifecycle）与防误报状态机闭环
 *
 * @author Ateng
 * @since 2026-10-26
 */
@Slf4j
public class AtengAdminNotificationNotifier extends AbstractStatusChangeNotifier implements SmartLifecycle {

    /**
     * K8s 优雅下线默认发版静默窗口期：60 秒
     */
    public static final Duration DEFAULT_DEREGISTRATION_SILENCE_WINDOW = Duration.ofSeconds(60);

    private enum LifecycleState {
        CREATED,
        RUNNING,
        STOPPED
    }

    private final ObjectProvider<NotificationTemplate> notificationTemplateProvider;
    private final AtengAdminServerProperties properties;
    private volatile ScheduledExecutorService scheduler;
    private final boolean internalScheduler;
    private final AtomicReference<LifecycleState> lifecycleState = new AtomicReference<>(LifecycleState.CREATED);
    private final ReentrantLock lifecycleLock = new ReentrantLock();

    /**
     * 正在处于防抖等待中的任务映射表：InstanceId -> ScheduledFuture
     */
    private final Map<InstanceId, ScheduledFuture<?>> pendingTasks = new ConcurrentHashMap<>();

    /**
     * 已确认发出过宕机告警的实例集合：用于保证仅对真正报过错的服务发送“恢复通知”
     */
    private final Set<InstanceId> alertedInstances = ConcurrentHashMap.newKeySet();

    /**
     * K8s 优雅下线实例缓存：InstanceId -> 优雅注销时间戳
     */
    private final Map<InstanceId, Long> deregisteredInstances = new ConcurrentHashMap<>();

    /**
     * 实例元数据敏感键集合（防止凭据与密钥泄露至协作群机器人卡片中）
     */
    private static final Set<String> SENSITIVE_METADATA_KEYS = Set.of(
            "user.password", "password", "token", "secret", "bearer-token", "actuator-token"
    );

    public AtengAdminNotificationNotifier(InstanceRepository repository,
                                          ObjectProvider<NotificationTemplate> notificationTemplateProvider,
                                          AtengAdminServerProperties properties) {
        this(repository, notificationTemplateProvider, properties, (ScheduledExecutorService) null);
    }

    public AtengAdminNotificationNotifier(InstanceRepository repository,
                                          ObjectProvider<NotificationTemplate> notificationTemplateProvider,
                                          AtengAdminServerProperties properties,
                                          ThreadPoolTaskScheduler taskScheduler) {
        this(repository, notificationTemplateProvider, properties,
                taskScheduler != null ? taskScheduler.getScheduledExecutor() : null);
    }

    public AtengAdminNotificationNotifier(InstanceRepository repository,
                                          ObjectProvider<NotificationTemplate> notificationTemplateProvider,
                                          AtengAdminServerProperties properties,
                                          ScheduledExecutorService scheduler) {
        super(repository);
        this.notificationTemplateProvider = notificationTemplateProvider;
        this.properties = properties;
        if (scheduler != null) {
            this.scheduler = scheduler;
            this.internalScheduler = false;
        } else {
            this.scheduler = createInternalScheduler();
            this.internalScheduler = true;
        }
    }

    private static ScheduledExecutorService createInternalScheduler() {
        ThreadPoolTaskScheduler defaultScheduler = new ThreadPoolTaskScheduler();
        defaultScheduler.setPoolSize(1);
        defaultScheduler.setThreadNamePrefix("sba-notify-debounce-");
        defaultScheduler.setDaemon(true);
        defaultScheduler.initialize();
        return defaultScheduler.getScheduledExecutor();
    }

    /**
     * 重写 SBA 顶层通知管道入口，确保注销事件无论是经由 SBA 事件流分发还是 Spring ApplicationContext 广播，
     * 均能 100% 被捕获并触发发版静默治理
     *
     * @param event 实例事件
     * @return 响应式 Mono 结果
     */
    @Override
    public Mono<Void> notify(InstanceEvent event) {
        if (event instanceof InstanceDeregisteredEvent deregisteredEvent) {
            onInstanceDeregistered(deregisteredEvent);
        }
        return super.notify(event);
    }

    /**
     * 监听 SBA 原生受控注销事件，捕获 K8s 发版下线
     *
     * @param event 注销事件
     */
    @EventListener
    public void onInstanceDeregistered(InstanceDeregisteredEvent event) {
        if (event == null || event.getInstance() == null) {
            return;
        }
        lifecycleLock.lock();
        try {
            if (lifecycleState.get() == LifecycleState.STOPPED) {
                return;
            }
            evictExpiredDeregistrations();
            InstanceId id = event.getInstance();
            deregisteredInstances.put(id, System.currentTimeMillis());
            log.info("[SBA Notifier] 实例 [{}] 收到受控注销信号，标记进入发版静默状态", id);

            // 取消该实例现存的防抖定时任务
            ScheduledFuture<?> future = pendingTasks.remove(id);
            if (future != null) {
                future.cancel(true);
            }
            alertedInstances.remove(id);
        } finally {
            lifecycleLock.unlock();
        }
    }

    @Override
    protected Mono<Void> doNotify(InstanceEvent event, Instance instance) {
        return Mono.fromRunnable(() -> {
            if (instance == null || !(event instanceof InstanceStatusChangedEvent statusEvent)) {
                return;
            }
            if (lifecycleState.get() == LifecycleState.STOPPED) {
                return;
            }

            InstanceId id = instance.getId();
            String currentStatus = statusEvent.getStatusInfo().getStatus();

            // 1. 检查是否在发版静默期内（注销后 60 秒内）
            Long deregisteredAt = deregisteredInstances.get(id);
            if (deregisteredAt != null) {
                if (System.currentTimeMillis() - deregisteredAt < DEFAULT_DEREGISTRATION_SILENCE_WINDOW.toMillis()) {
                    String appName = resolveInstanceName(instance, id);
                    log.info("[SBA Notifier] 实例 [{}] 属于正常优雅下线排空，静默忽略状态变迁", appName);
                    return;
                } else {
                    deregisteredInstances.remove(id);
                }
            }

            // 2. 状态跃迁判定
            if (isFaultStatus(currentStatus)) {
                handleFaultStatus(instance, statusEvent);
            } else if ("UP".equalsIgnoreCase(currentStatus)) {
                handleRecoveryStatus(instance, statusEvent);
            }
        });
    }

    private void handleFaultStatus(Instance instance, InstanceStatusChangedEvent statusEvent) {
        InstanceId id = instance.getId();
        String appName = resolveInstanceName(instance, id);
        String status = statusEvent.getStatusInfo().getStatus();

        // 1. 前置名单规则校验：未在白名单或命中黑名单直接跳过防抖告警
        if (!isServiceAlertAllowed(appName)) {
            log.info("[SBA Notifier] 实例 [{}] 命中告警名单过滤规则，静默跳过防抖告警", appName);
            return;
        }

        lifecycleLock.lock();
        try {
            ScheduledExecutorService activeScheduler = this.scheduler;
            if (lifecycleState.get() == LifecycleState.STOPPED
                    || activeScheduler == null
                    || activeScheduler.isShutdown()) {
                log.debug("[SBA Notifier] 通知器已停止，忽略实例 [{}] 的防抖告警排期", appName);
                return;
            }

            long delaySeconds = properties != null && properties.getDebounceDuration() != null
                    ? properties.getDebounceDuration().toSeconds()
                    : 30L;

            // 取消先前可能存在的未决任务
            ScheduledFuture<?> existing = pendingTasks.remove(id);
            if (existing != null) {
                existing.cancel(true);
            }

            log.warn("[SBA Notifier] 实例 [{}] 状态异常 [{}]，开启 {} 秒防抖倒计时...", appName, status, delaySeconds);

            ScheduledFuture<?>[] selfRef = new ScheduledFuture<?>[1];
            try {
                ScheduledFuture<?> scheduledTask = activeScheduler.schedule(
                        () -> executeDebouncedAlert(instance, statusEvent, id, status, selfRef),
                        delaySeconds,
                        TimeUnit.SECONDS
                );
                selfRef[0] = scheduledTask;
                if (scheduledTask != null && !scheduledTask.isDone()) {
                    pendingTasks.put(id, scheduledTask);
                }
            } catch (RejectedExecutionException ex) {
                log.debug("[SBA Notifier] 调度器拒绝排期，忽略实例 [{}] 的防抖告警: {}", appName, ex.getMessage());
            }
        } finally {
            lifecycleLock.unlock();
        }
    }

    private void executeDebouncedAlert(Instance instance,
                                       InstanceStatusChangedEvent statusEvent,
                                       InstanceId id,
                                       String status,
                                       ScheduledFuture<?>[] selfRef) {
        lifecycleLock.lock();
        try {
            ScheduledFuture<?> self = selfRef[0];
            if (lifecycleState.get() == LifecycleState.STOPPED || (self != null && self.isCancelled())) {
                return;
            }
            if (self != null) {
                pendingTasks.remove(id, self);
            } else {
                pendingTasks.remove(id);
            }
            String currentAppName = resolveInstanceName(instance, id);
            // 2. 防抖超时触发前二次名单校验，防止等待期间配置动态变更
            if (!isServiceAlertAllowed(currentAppName)) {
                log.info("[SBA Notifier] 实例 [{}] 在防抖超时后命中告警名单过滤规则，静默取消告警", currentAppName);
                return;
            }
            alertedInstances.add(id);
        } finally {
            lifecycleLock.unlock();
        }
        if (lifecycleState.get() != LifecycleState.STOPPED) {
            sendNotification(instance, "🚨 微服务实例异常宕机告警", status, statusEvent.getStatusInfo());
        }
    }

    private void handleRecoveryStatus(Instance instance, InstanceStatusChangedEvent statusEvent) {
        InstanceId id = instance.getId();
        String appName = resolveInstanceName(instance, id);
        boolean shouldSendRecovery;

        lifecycleLock.lock();
        try {
            if (lifecycleState.get() == LifecycleState.STOPPED) {
                return;
            }
            // 1. 取消挂起的防抖告警任务（成功自愈，不发宕机告警）
            ScheduledFuture<?> scheduled = pendingTasks.remove(id);
            if (scheduled != null) {
                scheduled.cancel(true);
                log.info("[SBA Notifier] 实例 [{}] 在防抖期内成功恢复 UP，已撤销报警任务", appName);
            }

            // 2. 仅当该实例先前确实发送过宕机告警时，才发送恢复卡片
            shouldSendRecovery = alertedInstances.remove(id) && isServiceAlertAllowed(appName);
        } finally {
            lifecycleLock.unlock();
        }

        if (shouldSendRecovery && lifecycleState.get() != LifecycleState.STOPPED) {
            sendNotification(instance, "🟢 微服务实例恢复正常通知", "UP", statusEvent.getStatusInfo());
        }
    }

    private void sendNotification(Instance instance, String title, String status, StatusInfo statusInfo) {
        NotificationTemplate notifier = notificationTemplateProvider != null ? notificationTemplateProvider.getIfAvailable() : null;
        if (notifier == null) {
            return;
        }

        String appName = resolveInstanceName(instance, instance != null ? instance.getId() : null);
        String serviceUrl = instance != null && instance.isRegistered() && instance.getRegistration() != null
                ? instance.getRegistration().getServiceUrl()
                : "-";
        String timeStr = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));

        StringBuilder contentBuilder = new StringBuilder();
        contentBuilder.append(String.format("""
                **应用名称**：%s
                **实例地址**：%s
                **当前状态**：%s
                **发生时间**：%s
                """, appName, serviceUrl, status, timeStr));

        // 1. 提取并附加应用版本
        String version = resolveVersion(instance);
        if (StringUtils.hasText(version)) {
            contentBuilder.append(String.format("**应用版本**：%s\n", version));
        }

        // 2. 提取并附加探针故障诊断详情 (Probe Details)
        StatusInfo infoToFormat = statusInfo != null ? statusInfo : (instance != null ? instance.getStatusInfo() : null);
        String probeDetails = formatProbeDetails(infoToFormat);
        if (StringUtils.hasText(probeDetails)) {
            contentBuilder.append("\n**探针诊断详情**：\n").append(probeDetails).append("\n");
        }

        // 3. 提取非敏感环境元数据 (Metadata)
        String metadataSummary = formatMetadata(instance);
        if (StringUtils.hasText(metadataSummary)) {
            contentBuilder.append("\n**实例元数据**：\n").append(metadataSummary).append("\n");
        }

        CardMessage card = CardMessage.of(title, contentBuilder.toString().trim());
        notifier.sendAsync(card);
    }

    private boolean isFaultStatus(String status) {
        if (!StringUtils.hasText(status)) {
            return false;
        }
        List<String> notifyStatuses = properties != null && properties.getAlert() != null
                ? properties.getAlert().getNotifyStatuses()
                : null;
        if (notifyStatuses == null || notifyStatuses.isEmpty()) {
            return "DOWN".equalsIgnoreCase(status) || "OFFLINE".equalsIgnoreCase(status);
        }
        return notifyStatuses.stream().anyMatch(s -> s.equalsIgnoreCase(status));
    }

    private boolean isServiceAlertAllowed(String serviceName) {
        if (!StringUtils.hasText(serviceName)) {
            return false;
        }
        if (properties == null || properties.getAlert() == null) {
            return true;
        }
        List<String> excluded = properties.getAlert().getExcludedServices();
        if (matchesAnyPattern(excluded, serviceName)) {
            return false;
        }
        List<String> included = properties.getAlert().getIncludedServices();
        if (included != null && !included.isEmpty()) {
            return matchesAnyPattern(included, serviceName);
        }
        return true;
    }

    private boolean matchesAnyPattern(List<String> patterns, String serviceName) {
        if (patterns == null || patterns.isEmpty()) {
            return false;
        }
        return patterns.stream().anyMatch(pattern -> matchServiceName(pattern, serviceName));
    }

    private boolean matchServiceName(String pattern, String serviceName) {
        if (!StringUtils.hasText(pattern)) {
            return false;
        }
        String p = pattern.trim();
        String s = serviceName.trim();
        if (p.equalsIgnoreCase(s)) {
            return true;
        }
        return PatternMatchUtils.simpleMatch(p.toLowerCase(Locale.ROOT), s.toLowerCase(Locale.ROOT));
    }

    private String resolveVersion(Instance instance) {
        if (instance == null) {
            return null;
        }
        if (instance.getBuildVersion() != null && StringUtils.hasText(instance.getBuildVersion().getValue())) {
            return instance.getBuildVersion().getValue();
        }
        if (instance.isRegistered() && instance.getRegistration() != null) {
            Map<String, String> metadata = instance.getRegistration().getMetadata();
            if (metadata != null) {
                if (StringUtils.hasText(metadata.get("version"))) {
                    return metadata.get("version");
                }
                if (StringUtils.hasText(metadata.get("build.version"))) {
                    return metadata.get("build.version");
                }
            }
        }
        return null;
    }

    private String formatProbeDetails(StatusInfo statusInfo) {
        if (statusInfo == null || statusInfo.getDetails() == null || statusInfo.getDetails().isEmpty()) {
            return null;
        }
        Map<String, Object> details = statusInfo.getDetails();
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Object> entry : details.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            if (value == null) {
                continue;
            }
            if (value instanceof Map<?, ?> nestedMap) {
                sb.append(String.format("- **%s**: ", key));
                Object nestedStatus = nestedMap.get("status");
                Object error = nestedMap.get("error");
                if (error == null && nestedMap.get("details") instanceof Map<?, ?> detailsMap) {
                    error = detailsMap.get("error");
                    if (error == null) {
                        error = detailsMap.get("message");
                    }
                }
                if (nestedStatus != null && error != null) {
                    sb.append(String.format("status=`%s`, error=`%s`\n", nestedStatus, error));
                } else if (nestedStatus != null) {
                    sb.append(String.format("status=`%s`\n", nestedStatus));
                } else if (error != null) {
                    sb.append(String.format("error=`%s`\n", error));
                } else {
                    sb.append(String.format("`%s`\n", nestedMap));
                }
            } else {
                sb.append(String.format("- **%s**: `%s`\n", key, value));
            }
        }
        return sb.toString().trim();
    }

    private String formatMetadata(Instance instance) {
        if (instance == null || !instance.isRegistered() || instance.getRegistration() == null) {
            return null;
        }
        Map<String, String> metadata = instance.getRegistration().getMetadata();
        if (metadata == null || metadata.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> entry : metadata.entrySet()) {
            String key = entry.getKey();
            if (key == null) {
                continue;
            }
            String lowerKey = key.toLowerCase(Locale.ROOT);
            if (SENSITIVE_METADATA_KEYS.stream().anyMatch(lowerKey::contains)) {
                continue;
            }
            if ("version".equalsIgnoreCase(key) || "build.version".equalsIgnoreCase(key)) {
                continue;
            }
            sb.append(String.format("- **%s**: `%s`\n", key, entry.getValue()));
        }
        return sb.toString().trim();
    }

    /**
     * 防御性驱逐超过发版静默窗口期的历史下线实例，防止长时间运行造成内存滞留
     */
    private void evictExpiredDeregistrations() {
        long now = System.currentTimeMillis();
        long silenceWindowMillis = DEFAULT_DEREGISTRATION_SILENCE_WINDOW.toMillis();
        deregisteredInstances.entrySet().removeIf(entry -> now - entry.getValue() > silenceWindowMillis);
    }

    /**
     * 统一提取实例应用名称，防御 null 与空字符串
     *
     * @param instance SBA 实例对象
     * @param id       实例标识
     * @return 实例展示名称
     */
    private String resolveInstanceName(Instance instance, InstanceId id) {
        if (instance != null && instance.isRegistered() && instance.getRegistration() != null) {
            String name = instance.getRegistration().getName();
            if (StringUtils.hasText(name)) {
                return name;
            }
        }
        return id != null ? id.getValue() : "UNKNOWN";
    }

    @Override
    public void start() {
        lifecycleLock.lock();
        try {
            if (this.internalScheduler && (this.scheduler == null || this.scheduler.isShutdown())) {
                this.scheduler = createInternalScheduler();
            }
            this.lifecycleState.set(LifecycleState.RUNNING);
        } finally {
            lifecycleLock.unlock();
        }
    }

    @Override
    public void stop() {
        lifecycleLock.lock();
        try {
            this.lifecycleState.set(LifecycleState.STOPPED);
            for (ScheduledFuture<?> future : this.pendingTasks.values()) {
                if (future != null) {
                    future.cancel(true);
                }
            }
            this.pendingTasks.clear();
            this.alertedInstances.clear();
            this.deregisteredInstances.clear();
            if (this.internalScheduler && this.scheduler != null && !this.scheduler.isShutdown()) {
                this.scheduler.shutdownNow();
            }
        } finally {
            lifecycleLock.unlock();
        }
    }

    @Override
    public boolean isRunning() {
        return this.lifecycleState.get() == LifecycleState.RUNNING;
    }

    boolean isSchedulerShutdown() {
        ScheduledExecutorService current = this.scheduler;
        return current == null || current.isShutdown();
    }

    public Map<InstanceId, ScheduledFuture<?>> getPendingTasks() {
        return Collections.unmodifiableMap(pendingTasks);
    }

    public Set<InstanceId> getAlertedInstances() {
        return Collections.unmodifiableSet(alertedInstances);
    }

    public Map<InstanceId, Long> getDeregisteredInstances() {
        return Collections.unmodifiableMap(deregisteredInstances);
    }
}
