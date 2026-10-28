package io.github.atengk.boot.admin.notifier;

import de.codecentric.boot.admin.server.domain.entities.Instance;
import de.codecentric.boot.admin.server.domain.entities.InstanceRepository;
import de.codecentric.boot.admin.server.domain.events.InstanceDeregisteredEvent;
import de.codecentric.boot.admin.server.domain.events.InstanceEvent;
import de.codecentric.boot.admin.server.domain.events.InstanceStatusChangedEvent;
import de.codecentric.boot.admin.server.domain.values.InstanceId;
import de.codecentric.boot.admin.server.notify.AbstractStatusChangeNotifier;
import io.github.atengk.boot.admin.properties.AtengAdminServerProperties;
import io.github.atengk.boot.notification.model.CardMessage;
import io.github.atengk.boot.notification.template.NotificationTemplate;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

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

    private final ObjectProvider<NotificationTemplate> notificationTemplateProvider;
    private final AtengAdminServerProperties properties;
    private final ScheduledExecutorService scheduler;
    private final AtomicBoolean running = new AtomicBoolean(false);

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
        } else {
            ThreadPoolTaskScheduler defaultScheduler = new ThreadPoolTaskScheduler();
            defaultScheduler.setPoolSize(1);
            defaultScheduler.setThreadNamePrefix("sba-notify-debounce-");
            defaultScheduler.setDaemon(true);
            defaultScheduler.initialize();
            this.scheduler = defaultScheduler.getScheduledExecutor();
        }
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
    }

    @Override
    protected Mono<Void> doNotify(InstanceEvent event, Instance instance) {
        return Mono.fromRunnable(() -> {
            if (instance == null || !(event instanceof InstanceStatusChangedEvent statusEvent)) {
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
            if ("DOWN".equalsIgnoreCase(currentStatus) || "OFFLINE".equalsIgnoreCase(currentStatus)) {
                handleFaultStatus(instance, currentStatus);
            } else if ("UP".equalsIgnoreCase(currentStatus)) {
                handleRecoveryStatus(instance);
            }
        });
    }

    private void handleFaultStatus(Instance instance, String status) {
        InstanceId id = instance.getId();
        long delaySeconds = properties != null && properties.getDebounceDuration() != null
                ? properties.getDebounceDuration().toSeconds()
                : 30L;

        // 取消先前可能存在的未决任务
        ScheduledFuture<?> existing = pendingTasks.remove(id);
        if (existing != null) {
            existing.cancel(true);
        }

        String appName = resolveInstanceName(instance, id);
        log.warn("[SBA Notifier] 实例 [{}] 状态异常 [{}]，开启 {} 秒防抖倒计时...", appName, status, delaySeconds);

        ScheduledFuture<?> scheduledTask = scheduler.schedule(() -> {
            pendingTasks.remove(id);
            alertedInstances.add(id);
            sendNotification(instance, "🚨 微服务实例异常宕机告警", status);
        }, delaySeconds, TimeUnit.SECONDS);

        pendingTasks.put(id, scheduledTask);
    }

    private void handleRecoveryStatus(Instance instance) {
        InstanceId id = instance.getId();
        String appName = resolveInstanceName(instance, id);

        // 1. 取消挂起的防抖告警任务（成功自愈，不发宕机告警）
        ScheduledFuture<?> scheduled = pendingTasks.remove(id);
        if (scheduled != null) {
            scheduled.cancel(true);
            log.info("[SBA Notifier] 实例 [{}] 在防抖期内成功恢复 UP，已撤销报警任务", appName);
        }

        // 2. 仅当该实例先前确实发送过宕机告警时，才发送恢复卡片
        if (alertedInstances.remove(id)) {
            sendNotification(instance, "🟢 微服务实例恢复正常通知", "UP");
        }
    }

    private void sendNotification(Instance instance, String title, String status) {
        NotificationTemplate notifier = notificationTemplateProvider != null ? notificationTemplateProvider.getIfAvailable() : null;
        if (notifier == null) {
            return;
        }

        String appName = resolveInstanceName(instance, instance.getId());
        String serviceUrl = instance.isRegistered() && instance.getRegistration() != null
                ? instance.getRegistration().getServiceUrl()
                : "-";
        String timeStr = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));

        String content = String.format("""
                **应用名称**：%s
                **实例地址**：%s
                **当前状态**：%s
                **发生时间**：%s
                """, appName, serviceUrl, status, timeStr);

        CardMessage card = CardMessage.of(title, content);
        notifier.sendAsync(card);
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
        this.running.set(true);
    }

    @Override
    public void stop() {
        if (this.running.compareAndSet(true, false)) {
            this.scheduler.shutdownNow();
            for (ScheduledFuture<?> future : this.pendingTasks.values()) {
                if (future != null) {
                    future.cancel(true);
                }
            }
            this.pendingTasks.clear();
            this.alertedInstances.clear();
            this.deregisteredInstances.clear();
        }
    }

    @Override
    public boolean isRunning() {
        return this.running.get();
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
