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

import java.time.Duration;
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
    @DisplayName("场景 5：SmartLifecycle 确定性生命周期治理，stop 时优雅清理线程池与内存队列")
    void shouldCleanUpResourcesDeterministicallyOnStop() {
        assertThat(notifier.isRunning()).isTrue();

        // 触发一次 DOWN 使得队列产生元素
        InstanceStatusChangedEvent downEvent = new InstanceStatusChangedEvent(instanceId, 1L, StatusInfo.ofDown());
        notifier.doNotify(downEvent, registeredInstance).block();
        assertThat(notifier.getPendingTasks()).isNotEmpty();

        // 停止容器与生命周期
        notifier.stop();

        assertThat(notifier.isRunning()).isFalse();
        verify(scheduler, times(1)).shutdownNow();
        verify(mockFuture, times(1)).cancel(true);
        assertThat(notifier.getPendingTasks()).isEmpty();
        assertThat(notifier.getAlertedInstances()).isEmpty();
        assertThat(notifier.getDeregisteredInstances()).isEmpty();
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
    @DisplayName("场景 7：三参数默认构造器使用 Spring 调度设施回退平滑初始化")
    void shouldSupportDefaultConstructorWithFallbackScheduler() {
        AtengAdminNotificationNotifier defaultNotifier = new AtengAdminNotificationNotifier(repository, notificationTemplateProvider, properties);
        assertThat(defaultNotifier).isNotNull();
        defaultNotifier.stop();
    }
}
