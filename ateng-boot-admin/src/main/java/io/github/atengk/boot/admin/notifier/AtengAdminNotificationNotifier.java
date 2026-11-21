package io.github.atengk.boot.admin.notifier;

import de.codecentric.boot.admin.server.domain.entities.Instance;
import de.codecentric.boot.admin.server.domain.entities.InstanceRepository;
import de.codecentric.boot.admin.server.domain.events.InstanceDeregisteredEvent;
import de.codecentric.boot.admin.server.domain.events.InstanceEvent;
import de.codecentric.boot.admin.server.domain.events.InstanceStatusChangedEvent;
import de.codecentric.boot.admin.server.domain.values.InstanceId;
import de.codecentric.boot.admin.server.domain.values.StatusInfo;
import de.codecentric.boot.admin.server.notify.AbstractStatusChangeNotifier;
import io.github.atengk.boot.admin.cluster.AdminClusterCoordinator;
import io.github.atengk.boot.admin.observability.AdminMetricsBinder;
import io.github.atengk.boot.admin.properties.AtengAdminServerProperties;
import io.github.atengk.boot.admin.silence.AdminSilenceManager;
import io.github.atengk.boot.notification.model.CardColor;
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

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
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
    private final AdminSilenceManager silenceManager;
    private final AdminClusterCoordinator clusterCoordinator;
    private final Clock clock;
    private volatile AdminMetricsBinder metricsBinder;
    private volatile ScheduledExecutorService scheduler;
    private final boolean internalScheduler;
    private final AtomicReference<LifecycleState> lifecycleState = new AtomicReference<>(LifecycleState.CREATED);
    private final ReentrantLock lifecycleLock = new ReentrantLock();

    /**
     * 正在处于防抖等待中的任务映射表：InstanceId -> ScheduledFuture
     */
    private final Map<InstanceId, ScheduledFuture<?>> pendingTasks = new ConcurrentHashMap<>();

    /**
     * 持续故障升级重提醒任务映射表：InstanceId -> ScheduledFuture
     */
    private final Map<InstanceId, ScheduledFuture<?>> reminderTasks = new ConcurrentHashMap<>();

    /**
     * 实例当前所处故障周期编号（用于在震荡/恢复交替场景隔离过期提醒任务）
     */
    private final Map<InstanceId, Long> faultCycleVersions = new ConcurrentHashMap<>();

    private final AtomicLong faultCycleSequence = new AtomicLong(0L);

    /**
     * 已确认发出过宕机告警的实例集合：用于保证仅对真正报过错的服务发送“恢复通知”
     */
    private final Set<InstanceId> alertedInstances = ConcurrentHashMap.newKeySet();

    /**
     * K8s 优雅下线实例缓存：InstanceId -> 优雅注销时间戳
     */
    private final Map<InstanceId, Long> deregisteredInstances = new ConcurrentHashMap<>();

    /**
     * 微窗口告警雪崩聚合状态（仅在 lifecycleLock 持锁期间读写）
     */
    private Instant stormWindowStart;
    private boolean stormSummaryTriggered;
    private final Map<InstanceId, StormFaultEntry> stormWindowEntries = new LinkedHashMap<>();

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
        this(repository, notificationTemplateProvider, properties, taskScheduler, null, null, null);
    }

    public AtengAdminNotificationNotifier(InstanceRepository repository,
                                          ObjectProvider<NotificationTemplate> notificationTemplateProvider,
                                          AtengAdminServerProperties properties,
                                          ScheduledExecutorService scheduler) {
        this(repository, notificationTemplateProvider, properties, scheduler, null, null, null);
    }

    public AtengAdminNotificationNotifier(InstanceRepository repository,
                                          ObjectProvider<NotificationTemplate> notificationTemplateProvider,
                                          AtengAdminServerProperties properties,
                                          ThreadPoolTaskScheduler taskScheduler,
                                          AdminSilenceManager silenceManager,
                                          AdminClusterCoordinator clusterCoordinator,
                                          Clock clock) {
        this(repository, notificationTemplateProvider, properties,
                taskScheduler != null ? taskScheduler.getScheduledExecutor() : null,
                silenceManager, clusterCoordinator, clock);
    }

    public AtengAdminNotificationNotifier(InstanceRepository repository,
                                          ObjectProvider<NotificationTemplate> notificationTemplateProvider,
                                          AtengAdminServerProperties properties,
                                          ScheduledExecutorService scheduler,
                                          AdminSilenceManager silenceManager,
                                          AdminClusterCoordinator clusterCoordinator,
                                          Clock clock) {
        super(repository);
        this.notificationTemplateProvider = notificationTemplateProvider;
        this.properties = properties;
        this.silenceManager = silenceManager;
        this.clusterCoordinator = clusterCoordinator;
        this.clock = clock != null ? clock : Clock.systemUTC();
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
            deregisteredInstances.put(id, clock.millis());
            log.info("[SBA Notifier] 实例 [{}] 收到受控注销信号，标记进入发版静默状态", id);

            clearFaultCycleLocked(id);
        } finally {
            lifecycleLock.unlock();
        }
    }

    /**
     * 彻底清理指定实例的所有防抖、静默、告警与重提醒状态（供僵尸实例清理回调防止内存滞留）
     *
     * @param id 实例标识
     */
    public void purgeInstanceState(InstanceId id) {
        if (id == null) {
            return;
        }
        lifecycleLock.lock();
        try {
            clearFaultCycleLocked(id);
            deregisteredInstances.remove(id);
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

            // 1. 检查是否在发版静默期内
            Long deregisteredAt = deregisteredInstances.get(id);
            if (deregisteredAt != null) {
                if (clock.millis() - deregisteredAt < resolveDeregistrationSilenceMillis()) {
                    String appName = resolveInstanceName(instance, id);
                    log.info("[SBA Notifier] 实例 [{}] 属于正常优雅下线排空，静默忽略状态变迁", appName);
                    recordMetric("silenced");
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

        // 1. 前置名单与动态维护静默校验：未通过直接跳过防抖排期
        if (!isServiceAlertAllowed(appName)) {
            log.info("[SBA Notifier] 实例 [{}] 命中告警名单过滤规则，静默跳过防抖告警", appName);
            recordMetric("silenced");
            return;
        }
        if (isMaintenanceSilenced(appName)) {
            log.info("[SBA Notifier] 实例 [{}] 处于动态维护静默窗口期，静默跳过故障排期", appName);
            recordMetric("silenced");
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

            // 同一故障周期内（防抖等待中或已发出首报未恢复），抑制 DOWN <-> OFFLINE 等震荡导致的重复排期
            if (pendingTasks.containsKey(id) || alertedInstances.contains(id)) {
                log.debug("[SBA Notifier] 实例 [{}] 处于同一故障周期震荡状态 [{}]，抑制重复排期与告警", appName, status);
                return;
            }

            long delayMillis = resolveDebounceDelayMillis();
            log.warn("[SBA Notifier] 实例 [{}] 状态异常 [{}]，开启 {} 毫秒防抖倒计时...", appName, status, delayMillis);

            ScheduledFuture<?>[] selfRef = new ScheduledFuture<?>[1];
            try {
                ScheduledFuture<?> scheduledTask = activeScheduler.schedule(
                        () -> executeDebouncedAlert(instance, statusEvent, id, status, selfRef),
                        delayMillis,
                        TimeUnit.MILLISECONDS
                );
                selfRef[0] = scheduledTask;
                if (scheduledTask != null && !scheduledTask.isDone()) {
                    pendingTasks.put(id, scheduledTask);
                    recordMetric("debounced");
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
        long cycleVersion;
        StormEvaluation stormEvaluation;
        String currentAppName = resolveInstanceName(instance, id);
        boolean maintenanceSilenced = isMaintenanceSilenced(currentAppName);

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
            // 防抖超时触发前二次名单与动态维护静默校验，防止等待期间配置/规则变更（静默判定提前在锁外完成，避免持锁阻塞 Redis I/O）
            if (!isServiceAlertAllowed(currentAppName)) {
                log.info("[SBA Notifier] 实例 [{}] 在防抖超时后命中告警名单过滤规则，静默取消告警", currentAppName);
                recordMetric("silenced");
                return;
            }
            if (maintenanceSilenced) {
                log.info("[SBA Notifier] 实例 [{}] 在防抖超时后命中维护静默规则，静默取消告警", currentAppName);
                recordMetric("silenced");
                return;
            }

            alertedInstances.add(id);
            cycleVersion = faultCycleSequence.incrementAndGet();
            faultCycleVersions.put(id, cycleVersion);

            stormEvaluation = evaluateStormAggregationLocked(id, currentAppName, status, instance);
            scheduleNextReminderLocked(instance, statusEvent, id, status, cycleVersion, 1);
        } finally {
            lifecycleLock.unlock();
        }

        if (lifecycleState.get() == LifecycleState.STOPPED) {
            return;
        }

        if (stormEvaluation.action == StormAction.SUPPRESS) {
            log.warn("[SBA Notifier] 微窗口告警雪崩已触发折叠，吸收散落实例 [{}] 告警", currentAppName);
            recordMetric("storm_aggregated");
            return;
        }

        if (stormEvaluation.action == StormAction.DISPATCH_SUMMARY) {
            long windowMillis = Math.max(1L, stormEvaluation.windowMillis);
            long bucket = stormEvaluation.windowStartMillis / windowMillis;
            String stormDedupKey = "alert:storm:" + windowMillis + ":" + bucket;
            recordMetric("storm_aggregated");
            if (tryClaimClusterDispatch(stormDedupKey)) {
                sendStormSummaryNotification(stormEvaluation.entries);
                recordMetric("sent");
            }
            return;
        }

        String dedupKey = "alert:fault:" + id.getValue() + ":" + statusEvent.getVersion() + ":"
                + (status != null ? status.toUpperCase(Locale.ROOT) : "UNKNOWN");
        if (tryClaimClusterDispatch(dedupKey)) {
            sendNotification(instance, "🚨 微服务实例异常宕机告警", status, statusEvent.getStatusInfo(), CardColor.RED);
            recordMetric("sent");
        }
    }

    private void executeEscalationReminder(Instance instance,
                                           InstanceStatusChangedEvent statusEvent,
                                           InstanceId id,
                                           String status,
                                           long cycleVersion,
                                           int reminderCount,
                                           ScheduledFuture<?>[] selfRef) {
        String appName = resolveInstanceName(instance, id);
        int maxCount = resolveReminderMaxCount();
        boolean maintenanceSilenced = isMaintenanceSilenced(appName);

        lifecycleLock.lock();
        try {
            ScheduledFuture<?> self = selfRef[0];
            if (lifecycleState.get() == LifecycleState.STOPPED
                    || (self != null && self.isCancelled())
                    || !alertedInstances.contains(id)
                    || !Objects.equals(cycleVersion, faultCycleVersions.get(id))) {
                return;
            }
            if (self != null) {
                reminderTasks.remove(id, self);
            } else {
                reminderTasks.remove(id);
            }
            if (!isServiceAlertAllowed(appName) || maintenanceSilenced) {
                log.info("[SBA Notifier] 实例 [{}] 在故障重提醒阶段被过滤或维护静默拦截，停止升级重提醒", appName);
                return;
            }
            if (reminderCount < maxCount) {
                scheduleNextReminderLocked(instance, statusEvent, id, status, cycleVersion, reminderCount + 1);
            }
        } finally {
            lifecycleLock.unlock();
        }

        if (lifecycleState.get() == LifecycleState.STOPPED) {
            return;
        }
        String dedupKey = "alert:reminder:" + id.getValue() + ":" + statusEvent.getVersion() + ":" + reminderCount;
        if (tryClaimClusterDispatch(dedupKey)) {
            String title = String.format("⚠️ 微服务持续故障升级重提醒 (%d/%d)", reminderCount, maxCount);
            sendNotification(instance, title, status, statusEvent.getStatusInfo(), CardColor.ORANGE);
            recordMetric("reminder");
        }
    }

    private void scheduleNextReminderLocked(Instance instance,
                                            InstanceStatusChangedEvent statusEvent,
                                            InstanceId id,
                                            String status,
                                            long cycleVersion,
                                            int nextCount) {
        AtengAdminServerProperties.ReminderProperties reminder = properties != null && properties.getAlert() != null
                ? properties.getAlert().getReminder()
                : null;
        if (reminder == null || !reminder.isEnabled() || nextCount > resolveReminderMaxCount()) {
            return;
        }
        Duration interval = reminder.getInterval();
        long intervalMillis = interval != null && !interval.isNegative() && !interval.isZero()
                ? interval.toMillis()
                : Duration.ofMinutes(15).toMillis();

        ScheduledExecutorService activeScheduler = this.scheduler;
        if (activeScheduler == null || activeScheduler.isShutdown()) {
            return;
        }

        ScheduledFuture<?>[] selfRef = new ScheduledFuture<?>[1];
        try {
            ScheduledFuture<?> future = activeScheduler.schedule(
                    () -> executeEscalationReminder(instance, statusEvent, id, status, cycleVersion, nextCount, selfRef),
                    intervalMillis,
                    TimeUnit.MILLISECONDS
            );
            selfRef[0] = future;
            if (future != null && !future.isDone()) {
                reminderTasks.put(id, future);
            }
        } catch (RejectedExecutionException ex) {
            log.debug("[SBA Notifier] 调度器拒绝排期故障重提醒，实例 [{}]: {}", resolveInstanceName(instance, id), ex.getMessage());
        }
    }

    private StormEvaluation evaluateStormAggregationLocked(InstanceId id,
                                                             String appName,
                                                             String status,
                                                             Instance instance) {
        AtengAdminServerProperties.StormAggregationProperties storm = properties != null && properties.getAlert() != null
                ? properties.getAlert().getStormAggregation()
                : null;
        if (storm == null || !storm.isEnabled()) {
            return new StormEvaluation(StormAction.DISPATCH_SINGLE, 0L, 10_000L, List.of());
        }

        long windowMillis = positiveMillis(storm.getWindow(), Duration.ofSeconds(10));
        int threshold = Math.max(1, storm.getThreshold());

        Instant now = clock.instant();
        Instant bucketStart = Instant.ofEpochMilli((now.toEpochMilli() / windowMillis) * windowMillis);
        if (stormWindowStart == null || !stormWindowStart.equals(bucketStart)) {
            stormWindowStart = bucketStart;
            stormWindowEntries.clear();
            stormSummaryTriggered = false;
        }

        if (stormSummaryTriggered) {
            return new StormEvaluation(StormAction.SUPPRESS, stormWindowStart.toEpochMilli(), windowMillis, List.of());
        }

        String serviceUrl = instance != null && instance.isRegistered() && instance.getRegistration() != null
                ? instance.getRegistration().getServiceUrl()
                : "-";
        stormWindowEntries.put(id, new StormFaultEntry(id.getValue(), appName, status, serviceUrl, now));

        if (stormWindowEntries.size() >= threshold) {
            stormSummaryTriggered = true;
            return new StormEvaluation(
                    StormAction.DISPATCH_SUMMARY,
                    stormWindowStart.toEpochMilli(),
                    windowMillis,
                    new ArrayList<>(stormWindowEntries.values())
            );
        }
        return new StormEvaluation(StormAction.DISPATCH_SINGLE, stormWindowStart.toEpochMilli(), windowMillis, List.of());
    }

    private void handleRecoveryStatus(Instance instance, InstanceStatusChangedEvent statusEvent) {
        InstanceId id = instance.getId();
        String appName = resolveInstanceName(instance, id);
        boolean maintenanceSilenced = isMaintenanceSilenced(appName);
        boolean shouldSendRecovery;

        lifecycleLock.lock();
        try {
            if (lifecycleState.get() == LifecycleState.STOPPED) {
                return;
            }
            ScheduledFuture<?> scheduled = pendingTasks.remove(id);
            if (scheduled != null) {
                scheduled.cancel(true);
                log.info("[SBA Notifier] 实例 [{}] 在防抖期内成功恢复 UP，已撤销报警任务", appName);
            }
            cancelReminderLocked(id);
            faultCycleVersions.remove(id);

            shouldSendRecovery = alertedInstances.remove(id)
                    && isServiceAlertAllowed(appName)
                    && !maintenanceSilenced;
        } finally {
            lifecycleLock.unlock();
        }

        if (shouldSendRecovery && lifecycleState.get() != LifecycleState.STOPPED) {
            String dedupKey = "alert:recovery:" + id.getValue() + ":" + statusEvent.getVersion();
            if (tryClaimClusterDispatch(dedupKey)) {
                sendNotification(instance, "🟢 微服务实例恢复正常通知", "UP", statusEvent.getStatusInfo(), CardColor.GREEN);
                recordMetric("sent");
            }
        }
    }

    private void sendNotification(Instance instance,
                                  String title,
                                  String status,
                                  StatusInfo statusInfo,
                                  CardColor headerColor) {
        NotificationTemplate notifier = notificationTemplateProvider != null ? notificationTemplateProvider.getIfAvailable() : null;
        if (notifier == null) {
            return;
        }

        InstanceId id = instance != null ? instance.getId() : null;
        String appName = resolveInstanceName(instance, id);
        String serviceUrl = instance != null && instance.isRegistered() && instance.getRegistration() != null
                ? instance.getRegistration().getServiceUrl()
                : "-";
        String timeStr = LocalDateTime.now(clock).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));

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

        CardMessage card = CardMessage.of(title, contentBuilder.toString().trim())
                .headerColor(headerColor);
        applyConsoleDetailAction(card, id);
        applyRouteOptions(card, appName);
        notifier.sendAsync(card);
    }

    private void sendStormSummaryNotification(List<StormFaultEntry> entries) {
        NotificationTemplate notifier = notificationTemplateProvider != null ? notificationTemplateProvider.getIfAvailable() : null;
        if (notifier == null || entries.isEmpty()) {
            return;
        }
        String timeStr = LocalDateTime.now(clock).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("**聚合数量**：%d 个故障实例已折叠，同窗口后续散落故障告警将被自动抑制\n", entries.size()));
        sb.append(String.format("**聚合时间**：%s\n\n**涉及故障实例**：\n", timeStr));
        for (StormFaultEntry entry : entries) {
            sb.append(String.format("- **%s** (`%s`, `%s`, %s)\n",
                    entry.appName, entry.instanceId, entry.status, entry.serviceUrl));
        }

        CardMessage card = CardMessage.of("⛈️ 微服务告警雪崩折叠摘要", sb.toString().trim())
                .headerColor(CardColor.RED);
        applyRouteOptions(card, entries.get(0).appName);
        notifier.sendAsync(card);
    }

    private void applyConsoleDetailAction(CardMessage card, InstanceId id) {
        String consoleBaseUrl = properties != null && properties.getAlert() != null
                ? properties.getAlert().getConsoleBaseUrl()
                : null;
        if (!StringUtils.hasText(consoleBaseUrl) || id == null || !StringUtils.hasText(id.getValue())) {
            return;
        }
        String normalizedBase = consoleBaseUrl.trim();
        while (normalizedBase.endsWith("/")) {
            normalizedBase = normalizedBase.substring(0, normalizedBase.length() - 1);
        }
        String detailUrl = normalizedBase + "/#/instances/" + id.getValue() + "/details";
        card.singleAction("查看控制台详情", detailUrl);
    }

    private void applyRouteOptions(CardMessage card, String appName) {
        if (properties == null || properties.getAlert() == null || properties.getAlert().getRoutes() == null) {
            return;
        }
        for (AtengAdminServerProperties.AlertRouteProperties route : properties.getAlert().getRoutes()) {
            if (route == null || !matchServiceName(route.getServicePattern(), appName)) {
                continue;
            }
            if (StringUtils.hasText(route.getChannel())) {
                card.channel(route.getChannel().trim());
            }
            if (route.isMentionAll()) {
                card.atAll();
            }
            if (route.getMentionMobiles() != null && !route.getMentionMobiles().isEmpty()) {
                card.atMobiles(route.getMentionMobiles());
            }
            if (route.getMentionUserIds() != null && !route.getMentionUserIds().isEmpty()) {
                card.atUserIds(route.getMentionUserIds());
            }
            return;
        }
    }

    private boolean isMaintenanceSilenced(String serviceName) {
        return silenceManager != null && silenceManager.isSilenced(serviceName);
    }

    private boolean tryClaimClusterDispatch(String dedupKey) {
        if (clusterCoordinator == null) {
            return true;
        }
        Duration ttl = properties != null && properties.getCluster() != null && properties.getCluster().getAlertDedupTtl() != null
                ? properties.getCluster().getAlertDedupTtl()
                : Duration.ofSeconds(60);
        return clusterCoordinator.tryMarkAlertDispatched(dedupKey, ttl);
    }

    private long resolveDebounceDelayMillis() {
        if (properties != null && properties.getDebounceDuration() != null && !properties.getDebounceDuration().isNegative()) {
            return properties.getDebounceDuration().toMillis();
        }
        return 30_000L;
    }

    private long resolveDeregistrationSilenceMillis() {
        if (properties != null
                && properties.getAlert() != null
                && properties.getAlert().getDeregistrationSilenceDuration() != null
                && !properties.getAlert().getDeregistrationSilenceDuration().isNegative()) {
            return properties.getAlert().getDeregistrationSilenceDuration().toMillis();
        }
        return DEFAULT_DEREGISTRATION_SILENCE_WINDOW.toMillis();
    }

    private int resolveReminderMaxCount() {
        if (properties != null && properties.getAlert() != null && properties.getAlert().getReminder() != null) {
            return Math.max(0, properties.getAlert().getReminder().getMaxCount());
        }
        return 3;
    }

    private void clearFaultCycleLocked(InstanceId id) {
        ScheduledFuture<?> future = pendingTasks.remove(id);
        if (future != null) {
            future.cancel(true);
        }
        cancelReminderLocked(id);
        faultCycleVersions.remove(id);
        alertedInstances.remove(id);
    }

    private long positiveMillis(Duration duration, Duration fallback) {
        return duration != null && !duration.isNegative() && !duration.isZero()
                ? duration.toMillis()
                : fallback.toMillis();
    }

    private void cancelReminderLocked(InstanceId id) {
        ScheduledFuture<?> reminderFuture = reminderTasks.remove(id);
        if (reminderFuture != null) {
            reminderFuture.cancel(true);
        }
    }

    private enum StormAction {
        DISPATCH_SINGLE,
        DISPATCH_SUMMARY,
        SUPPRESS
    }

    private record StormEvaluation(StormAction action, long windowStartMillis, long windowMillis, List<StormFaultEntry> entries) {
    }

    private record StormFaultEntry(String instanceId, String appName, String status, String serviceUrl, Instant occurredAt) {
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
        long now = clock.millis();
        long silenceWindowMillis = resolveDeregistrationSilenceMillis();
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
            for (ScheduledFuture<?> reminderFuture : this.reminderTasks.values()) {
                if (reminderFuture != null) {
                    reminderFuture.cancel(true);
                }
            }
            this.pendingTasks.clear();
            this.reminderTasks.clear();
            this.faultCycleVersions.clear();
            this.alertedInstances.clear();
            this.deregisteredInstances.clear();
            this.stormWindowEntries.clear();
            this.stormWindowStart = null;
            this.stormSummaryTriggered = false;
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

    public boolean isSchedulerShutdown() {
        ScheduledExecutorService current = this.scheduler;
        return current == null || current.isShutdown();
    }

    /**
     * 获取当前雪崩折叠统计微窗口内累积的故障实例条目数（线程安全快照）
     *
     * @return 当前统计窗口条目数
     */
    public int getStormWindowEntryCount() {
        lifecycleLock.lock();
        try {
            return this.stormWindowEntries.size();
        } finally {
            lifecycleLock.unlock();
        }
    }

    /**
     * 注入低基数自观测指标绑定器（支持装配阶段解耦挂载）
     *
     * @param metricsBinder 服务端指标绑定器
     */
    public void setMetricsBinder(AdminMetricsBinder metricsBinder) {
        this.metricsBinder = metricsBinder;
    }

    private void recordMetric(String type) {
        AdminMetricsBinder binder = this.metricsBinder;
        if (binder != null) {
            binder.recordAlert(type);
        }
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
