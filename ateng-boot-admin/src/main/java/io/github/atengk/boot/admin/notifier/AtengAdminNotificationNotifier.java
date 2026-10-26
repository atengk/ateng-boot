package io.github.atengk.boot.admin.notifier;

import de.codecentric.boot.admin.server.domain.entities.Instance;
import de.codecentric.boot.admin.server.domain.entities.InstanceRepository;
import de.codecentric.boot.admin.server.domain.events.InstanceDeregisteredEvent;
import de.codecentric.boot.admin.server.domain.events.InstanceEvent;
import de.codecentric.boot.admin.server.domain.events.InstanceStatusChangedEvent;
import de.codecentric.boot.admin.server.domain.values.InstanceId;
import de.codecentric.boot.admin.server.notify.AbstractStatusChangeNotifier;
import io.github.atengk.boot.admin.properties.AtengAdminServerProperties;
import io.github.atengk.boot.core.concurrent.NamedThreadFactory;
import io.github.atengk.boot.notification.model.CardMessage;
import io.github.atengk.boot.notification.template.NotificationTemplate;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.event.EventListener;
import reactor.core.publisher.Mono;

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
        this(repository, notificationTemplateProvider, properties,
                Executors.newSingleThreadScheduledExecutor(new NamedThreadFactory("sba-notify-debounce-", true)));
    }

    public AtengAdminNotificationNotifier(InstanceRepository repository,
                                          ObjectProvider<NotificationTemplate> notificationTemplateProvider,
                                          AtengAdminServerProperties properties,
                                          ScheduledExecutorService scheduler) {
        super(repository);
        this.notificationTemplateProvider = notificationTemplateProvider;
        this.properties = properties;
        this.scheduler = scheduler;
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
                if (System.currentTimeMillis() - deregisteredAt < 60_000L) {
                    String appName = instance.isRegistered() ? instance.getRegistration().getName() : id.getValue();
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

        String appName = instance.isRegistered() ? instance.getRegistration().getName() : id.getValue();
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
        String appName = instance.isRegistered() ? instance.getRegistration().getName() : id.getValue();

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

        String appName = instance.isRegistered() ? instance.getRegistration().getName() : instance.getId().getValue();
        String serviceUrl = instance.isRegistered() ? instance.getRegistration().getServiceUrl() : "-";
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
