package io.github.atengk.boot.admin.notifier;

import de.codecentric.boot.admin.server.domain.entities.Instance;
import de.codecentric.boot.admin.server.domain.entities.InstanceRepository;
import de.codecentric.boot.admin.server.domain.events.InstanceDeregisteredEvent;
import de.codecentric.boot.admin.server.domain.events.InstanceStatusChangedEvent;
import de.codecentric.boot.admin.server.domain.values.InstanceId;
import de.codecentric.boot.admin.server.domain.values.Registration;
import de.codecentric.boot.admin.server.domain.values.StatusInfo;
import io.github.atengk.boot.admin.properties.AtengAdminServerProperties;
import io.github.atengk.boot.notification.model.CardMessage;
import io.github.atengk.boot.notification.template.NotificationTemplate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/**
 * AtengAdminNotificationNotifier 单元测试
 * 验证 30 秒防抖队列、自愈撤销、K8s 优雅下线发版静默与“仅对真实故障触发恢复”的闭环状态机
 *
 * @author Ateng
 * @since 2026-10-26
 */
@DisplayName("AtengAdminNotificationNotifier 状态机与防抖告警单元测试")
class AtengAdminNotificationNotifierTest {

    private InstanceRepository repository;
    private NotificationTemplate notificationTemplate;
    private ObjectProvider<NotificationTemplate> notificationTemplateProvider;
    private AtengAdminServerProperties properties;
    private ScheduledExecutorService scheduler;
    private ScheduledFuture<?> mockFuture;
    private AtomicReference<Runnable> capturedScheduledTask;
    private AtengAdminNotificationNotifier notifier;

    private final InstanceId instanceId = InstanceId.of("order-svc-001");
    private Instance registeredInstance;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        repository = mock(InstanceRepository.class);
        notificationTemplate = mock(NotificationTemplate.class);
        notificationTemplateProvider = mock(ObjectProvider.class);
        when(notificationTemplateProvider.getIfAvailable()).thenReturn(notificationTemplate);

        properties = new AtengAdminServerProperties();
        properties.setDebounceDuration(Duration.ofSeconds(30));

        scheduler = mock(ScheduledExecutorService.class);
        mockFuture = mock(ScheduledFuture.class);
        capturedScheduledTask = new AtomicReference<>();

        when(scheduler.schedule(any(Runnable.class), anyLong(), any(TimeUnit.class)))
                .thenAnswer(invocation -> {
                    capturedScheduledTask.set(invocation.getArgument(0));
                    return mockFuture;
                });

        notifier = new AtengAdminNotificationNotifier(repository, notificationTemplateProvider, properties, scheduler);
        notifier.start();

        Registration registration = Registration.create("order-service", "http://order:8080/actuator/health")
                .serviceUrl("http://order:8080")
                .build();
        registeredInstance = Instance.create(instanceId).register(registration);
    }

    @Test
    @DisplayName("场景 1：实例短暂异常但在防抖窗口内成功自愈，撤销定时告警且不发恢复卡片")
    void shouldCancelPendingAlarmWhenInstanceRecoversBeforeDebounceTimeout() {
        // 1. 实例变为 DOWN
        InstanceStatusChangedEvent downEvent = new InstanceStatusChangedEvent(instanceId, 1L, StatusInfo.ofDown());
        notifier.doNotify(downEvent, registeredInstance).block();

        // 验证已安排防抖定时任务，延时为 30 秒
        verify(scheduler, times(1)).schedule(any(Runnable.class), eq(30L), eq(TimeUnit.SECONDS));
        assertThat(notifier.getPendingTasks()).containsKey(instanceId);
        assertThat(notifier.getAlertedInstances()).doesNotContain(instanceId);

        // 2. 实例在防抖期内自愈变为 UP
        InstanceStatusChangedEvent upEvent = new InstanceStatusChangedEvent(instanceId, 2L, StatusInfo.ofUp());
        notifier.doNotify(upEvent, registeredInstance).block();

        // 验证定时任务被取消，防抖任务被移除
        verify(mockFuture, times(1)).cancel(true);
        assertThat(notifier.getPendingTasks()).doesNotContainKey(instanceId);

        // 验证未向机器人发送过任何宕机或恢复告警卡片（零误报骚扰）
        verify(notificationTemplate, never()).sendAsync(any());
        assertThat(notifier.getAlertedInstances()).doesNotContain(instanceId);
    }

    @Test
    @DisplayName("场景 2 与 3：防抖倒计时超时真实触发故障告警，后续恢复时闭环推送恢复通知")
    void shouldSendAlarmOnDebounceTimeoutAndSendRecoveryWhenBackUp() {
        // 1. 实例变为 DOWN 触发防抖调度
        InstanceStatusChangedEvent downEvent = new InstanceStatusChangedEvent(instanceId, 1L, StatusInfo.ofDown());
        notifier.doNotify(downEvent, registeredInstance).block();
        assertThat(capturedScheduledTask.get()).isNotNull();

        // 2. 模拟 30 秒倒计时结束，定时任务真实被调度执行
        capturedScheduledTask.get().run();

        // 验证向机器人发送了宕机卡片，且实例加入 alertedInstances
        ArgumentCaptor<CardMessage> cardCaptor = ArgumentCaptor.forClass(CardMessage.class);
        verify(notificationTemplate, times(1)).sendAsync(cardCaptor.capture());
        assertThat(cardCaptor.getValue().getTitle()).contains("微服务实例异常宕机告警");
        assertThat(cardCaptor.getValue().getContent()).contains("order-service");
        assertThat(notifier.getAlertedInstances()).contains(instanceId);
        assertThat(notifier.getPendingTasks()).doesNotContainKey(instanceId);

        // 3. 实例后续恢复为 UP
        InstanceStatusChangedEvent upEvent = new InstanceStatusChangedEvent(instanceId, 2L, StatusInfo.ofUp());
        notifier.doNotify(upEvent, registeredInstance).block();

        // 验证因该实例确实报过故障，因而成功发送“恢复正常通知”
        verify(notificationTemplate, times(2)).sendAsync(cardCaptor.capture());
        assertThat(cardCaptor.getValue().getTitle()).contains("微服务实例恢复正常通知");
        assertThat(cardCaptor.getValue().getContent()).contains("order-service");

        // 验证从已告警集合中清理
        assertThat(notifier.getAlertedInstances()).doesNotContain(instanceId);
    }

    @Test
    @DisplayName("场景 4：K8s 优雅停机注销进入 60 秒发版静默期，静默忽略故障跃迁，零发版误报")
    void shouldSilenceStatusChangesWhenInstanceDeregisteredGracefully() {
        // 1. 收到 K8s 优雅下线注销事件
        InstanceDeregisteredEvent deregisteredEvent = new InstanceDeregisteredEvent(instanceId, 1L);
        notifier.onInstanceDeregistered(deregisteredEvent);

        assertThat(notifier.getDeregisteredInstances()).containsKey(instanceId);

        // 2. 下线下游随后收到 OFFLINE 变迁
        InstanceStatusChangedEvent offlineEvent = new InstanceStatusChangedEvent(instanceId, 2L, StatusInfo.ofOffline());
        notifier.doNotify(offlineEvent, registeredInstance).block();

        // 验证静默忽略：未安排防抖任务，未触发机器人通知
        verify(scheduler, never()).schedule(any(Runnable.class), anyLong(), any(TimeUnit.class));
        verify(notificationTemplate, never()).sendAsync(any());
        assertThat(notifier.getPendingTasks()).isEmpty();
    }

    @Test
    @DisplayName("场景 5：SmartLifecycle 确定性生命周期治理，stop 时清理队列且不误杀外部注入调度器")
    void shouldCleanUpResourcesDeterministicallyOnStop() {
        assertThat(notifier.isRunning()).isTrue();

        // 触发一次 DOWN 使得队列产生元素
        InstanceStatusChangedEvent downEvent = new InstanceStatusChangedEvent(instanceId, 1L, StatusInfo.ofDown());
        notifier.doNotify(downEvent, registeredInstance).block();
        assertThat(notifier.getPendingTasks()).isNotEmpty();

        // 停止容器与生命周期
        notifier.stop();

        assertThat(notifier.isRunning()).isFalse();
        verify(scheduler, never()).shutdownNow();
        verify(mockFuture, times(1)).cancel(true);
        assertThat(notifier.getPendingTasks()).isEmpty();
        assertThat(notifier.getAlertedInstances()).isEmpty();
        assertThat(notifier.getDeregisteredInstances()).isEmpty();
    }

    @Test
    @DisplayName("场景 5b：真实外部线程池不被 stop 误杀，内部自建调度器在 stop 时确定性关闭且拒绝后继排期")
    void shouldShutdownOnlyInternalSchedulerOnStop() {
        ScheduledExecutorService externalExecutor = Executors.newSingleThreadScheduledExecutor();
        try {
            AtengAdminNotificationNotifier externalNotifier =
                    new AtengAdminNotificationNotifier(repository, notificationTemplateProvider, properties, externalExecutor);
            externalNotifier.start();
            externalNotifier.stop();
            assertThat(externalExecutor.isShutdown()).isFalse();

            InstanceStatusChangedEvent lateEvent = new InstanceStatusChangedEvent(instanceId, 1L, StatusInfo.ofDown());
            externalNotifier.doNotify(lateEvent, registeredInstance).block();
            assertThat(externalNotifier.getPendingTasks()).isEmpty();
        } finally {
            externalExecutor.shutdownNow();
        }

        AtengAdminNotificationNotifier internalNotifier =
                new AtengAdminNotificationNotifier(repository, notificationTemplateProvider, properties);
        internalNotifier.start();
        assertThat(internalNotifier.isSchedulerShutdown()).isFalse();
        internalNotifier.stop();

        InstanceStatusChangedEvent downEvent = new InstanceStatusChangedEvent(instanceId, 1L, StatusInfo.ofDown());
        internalNotifier.doNotify(downEvent, registeredInstance).block();
        assertThat(internalNotifier.isRunning()).isFalse();
        assertThat(internalNotifier.isSchedulerShutdown()).isTrue();
        assertThat(internalNotifier.getPendingTasks()).isEmpty();
    }

    @Test
    @DisplayName("场景 6：SBA 反应式事件管道派发注销事件时，直接通过 notify(InstanceEvent) 拦截并进入发版静默")
    void shouldTriggerGracefulDeregistrationViaReactiveNotifyPipeline() {
        when(repository.find(instanceId)).thenReturn(reactor.core.publisher.Mono.empty());
        InstanceDeregisteredEvent deregisteredEvent = new InstanceDeregisteredEvent(instanceId, 1L);
        notifier.notify(deregisteredEvent).block();

        assertThat(notifier.getDeregisteredInstances()).containsKey(instanceId);
    }

    @Test
    @DisplayName("场景 7：三参数默认构造器使用 Spring 调度设施回退平滑初始化；未 start 直接 stop 亦须停机拦截")
    void shouldSupportDefaultConstructorWithFallbackScheduler() {
        AtengAdminNotificationNotifier defaultNotifier = new AtengAdminNotificationNotifier(repository, notificationTemplateProvider, properties);
        assertThat(defaultNotifier).isNotNull();
        assertThat(defaultNotifier.isSchedulerShutdown()).isFalse();
        defaultNotifier.stop();
        assertThat(defaultNotifier.isSchedulerShutdown()).isTrue();

        InstanceStatusChangedEvent downEvent = new InstanceStatusChangedEvent(instanceId, 1L, StatusInfo.ofDown());
        defaultNotifier.doNotify(downEvent, registeredInstance).block();
        assertThat(defaultNotifier.getPendingTasks()).isEmpty();
        verify(notificationTemplate, never()).sendAsync(any());
    }

    @Test
    @DisplayName("场景 7b：内部调度器 stop 后再次 start 时能够平滑重建并恢复告警调度")
    void shouldRecreateInternalSchedulerWhenRestartedAfterStop() {
        properties.setDebounceDuration(Duration.ZERO);
        AtengAdminNotificationNotifier internalNotifier =
                new AtengAdminNotificationNotifier(repository, notificationTemplateProvider, properties);
        try {
            internalNotifier.start();
            internalNotifier.stop();
            assertThat(internalNotifier.isSchedulerShutdown()).isTrue();

            internalNotifier.start();
            assertThat(internalNotifier.isRunning()).isTrue();
            assertThat(internalNotifier.isSchedulerShutdown()).isFalse();

            InstanceStatusChangedEvent downEvent = new InstanceStatusChangedEvent(instanceId, 1L, StatusInfo.ofDown());
            internalNotifier.doNotify(downEvent, registeredInstance).block();

            verify(notificationTemplate, timeout(2000).times(1)).sendAsync(any());
        } finally {
            internalNotifier.stop();
        }
    }

    @Test
    @DisplayName("场景 7c：已排期防抖任务在 stop 后即使被外部调度线程执行，也不得再修改状态或发信")
    void shouldIgnoreLateScheduledCallbackExecutionAfterStop() {
        InstanceStatusChangedEvent downEvent = new InstanceStatusChangedEvent(instanceId, 1L, StatusInfo.ofDown());
        notifier.doNotify(downEvent, registeredInstance).block();
        Runnable scheduledCallback = capturedScheduledTask.get();
        assertThat(scheduledCallback).isNotNull();

        notifier.stop();
        scheduledCallback.run();

        assertThat(notifier.getPendingTasks()).isEmpty();
        assertThat(notifier.getAlertedInstances()).isEmpty();
        verify(notificationTemplate, never()).sendAsync(any());
    }

    @Test
    @DisplayName("场景 7d：并发执行 doNotify 与 stop 时不得向外部调度器泄漏待执行任务")
    void shouldPreventTaskLeakUnderConcurrentStopAndNotify() {
        ThreadPoolTaskScheduler externalTaskScheduler = new ThreadPoolTaskScheduler();
        externalTaskScheduler.setPoolSize(2);
        externalTaskScheduler.setThreadNamePrefix("test-ext-sba-");
        externalTaskScheduler.setDaemon(true);
        externalTaskScheduler.initialize();
        try {
            for (int i = 0; i < 40; i++) {
                AtengAdminNotificationNotifier concurrentNotifier =
                        new AtengAdminNotificationNotifier(repository, notificationTemplateProvider, properties, externalTaskScheduler);
                concurrentNotifier.start();
                CountDownLatch startGate = new CountDownLatch(1);
                InstanceStatusChangedEvent downEvent = new InstanceStatusChangedEvent(instanceId, i + 1L, StatusInfo.ofDown());

                CompletableFuture<Void> notifyFuture = CompletableFuture.runAsync(() -> {
                    awaitQuietly(startGate);
                    concurrentNotifier.doNotify(downEvent, registeredInstance).block();
                });
                CompletableFuture<Void> stopFuture = CompletableFuture.runAsync(() -> {
                    awaitQuietly(startGate);
                    concurrentNotifier.stop();
                });

                startGate.countDown();
                CompletableFuture.allOf(notifyFuture, stopFuture).join();

                assertThat(concurrentNotifier.isRunning()).isFalse();
                assertThat(concurrentNotifier.getPendingTasks()).isEmpty();
            }
        } finally {
            externalTaskScheduler.destroy();
        }
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    @DisplayName("场景 8：命中 excluded-services 黑名单的服务发生故障时静默忽略告警")
    void shouldSuppressAlertWhenServiceMatchesExcludedList() {
        properties.getAlert().getExcludedServices().add("order-*");

        InstanceStatusChangedEvent downEvent = new InstanceStatusChangedEvent(instanceId, 1L, StatusInfo.ofDown());
        notifier.doNotify(downEvent, registeredInstance).block();

        verify(scheduler, never()).schedule(any(Runnable.class), anyLong(), any(TimeUnit.class));
        assertThat(notifier.getPendingTasks()).doesNotContainKey(instanceId);
    }

    @Test
    @DisplayName("场景 9：配置 included-services 白名单时，未在白名单中的服务发生故障时静默忽略告警")
    void shouldSuppressAlertWhenServiceNotInIncludedList() {
        properties.getAlert().getIncludedServices().add("payment-*");

        InstanceStatusChangedEvent downEvent = new InstanceStatusChangedEvent(instanceId, 1L, StatusInfo.ofDown());
        notifier.doNotify(downEvent, registeredInstance).block();

        verify(scheduler, never()).schedule(any(Runnable.class), anyLong(), any(TimeUnit.class));
        assertThat(notifier.getPendingTasks()).doesNotContainKey(instanceId);
    }

    @Test
    @DisplayName("场景 10：配置 included-services 白名单时，白名单内的服务正常触发防抖调度")
    void shouldAllowAlertWhenServiceMatchesIncludedList() {
        properties.getAlert().getIncludedServices().add("order-*");

        InstanceStatusChangedEvent downEvent = new InstanceStatusChangedEvent(instanceId, 1L, StatusInfo.ofDown());
        notifier.doNotify(downEvent, registeredInstance).block();

        verify(scheduler, times(1)).schedule(any(Runnable.class), eq(30L), eq(TimeUnit.SECONDS));
        assertThat(notifier.getPendingTasks()).containsKey(instanceId);
    }

    @Test
    @DisplayName("场景 11：配置 notify-statuses 状态白名单时，非目标状态变迁被静默忽略")
    void shouldSuppressAlertWhenStatusNotInNotifyStatuses() {
        properties.getAlert().setNotifyStatuses(List.of("DOWN"));

        InstanceStatusChangedEvent offlineEvent = new InstanceStatusChangedEvent(instanceId, 1L, StatusInfo.ofOffline());
        notifier.doNotify(offlineEvent, registeredInstance).block();

        verify(scheduler, never()).schedule(any(Runnable.class), anyLong(), any(TimeUnit.class));
        assertThat(notifier.getPendingTasks()).doesNotContainKey(instanceId);
    }

    @Test
    @DisplayName("场景 12：富文本告警卡片能够自动提取应用版本与健康探针详情，并深度脱敏安全凭据")
    void shouldAppendProbeDetailsAndVersionToCardMessage() {
        Registration registrationWithMeta = Registration.create("order-service", "http://order:8080/actuator/health")
                .serviceUrl("http://order:8080")
                .metadata("version", "v3.2.1")
                .metadata("region", "cn-hangzhou")
                .metadata("user.password", "SUPER_SECRET_PWD")
                .metadata("bearer-token", "RAW_JWT_TOKEN")
                .build();
        Instance enrichedInstance = Instance.create(instanceId).register(registrationWithMeta);

        Map<String, Object> details = Map.of(
                "diskSpace", Map.of("status", "DOWN", "error", "Disk is 99% full"),
                "db", Map.of("status", "DOWN", "error", "Connection timeout to MySQL")
        );
        StatusInfo statusInfoWithDetails = StatusInfo.valueOf("DOWN", details);
        InstanceStatusChangedEvent downEvent = new InstanceStatusChangedEvent(instanceId, 1L, statusInfoWithDetails);

        notifier.doNotify(downEvent, enrichedInstance).block();
        assertThat(capturedScheduledTask.get()).isNotNull();

        // 执行防抖超时任务
        capturedScheduledTask.get().run();

        ArgumentCaptor<CardMessage> cardCaptor = ArgumentCaptor.forClass(CardMessage.class);
        verify(notificationTemplate, times(1)).sendAsync(cardCaptor.capture());

        CardMessage sentCard = cardCaptor.getValue();
        String content = sentCard.getContent();

        // 断言富文本诊断字段呈现
        assertThat(content).contains("**应用版本**：v3.2.1");
        assertThat(content).contains("**探针诊断详情**：");
        assertThat(content).contains("diskSpace");
        assertThat(content).contains("Disk is 99% full");
        assertThat(content).contains("db");
        assertThat(content).contains("Connection timeout to MySQL");

        // 断言元数据提取与凭据脱敏保护
        assertThat(content).contains("**实例元数据**：");
        assertThat(content).contains("region");
        assertThat(content).contains("cn-hangzhou");
        assertThat(content).doesNotContain("SUPER_SECRET_PWD");
        assertThat(content).doesNotContain("RAW_JWT_TOKEN");
        assertThat(content).doesNotContain("user.password");
        assertThat(content).doesNotContain("bearer-token");
    }
}
