package io.github.atengk.boot.admin.scheduler;

import de.codecentric.boot.admin.server.domain.entities.Instance;
import de.codecentric.boot.admin.server.domain.entities.InstanceRepository;
import de.codecentric.boot.admin.server.domain.values.InstanceId;
import de.codecentric.boot.admin.server.domain.values.Registration;
import de.codecentric.boot.admin.server.domain.values.StatusInfo;
import de.codecentric.boot.admin.server.services.InstanceRegistry;
import io.github.atengk.boot.admin.properties.AtengAdminServerProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ZombieInstanceCleanupScheduler 僵尸实例自动清理调度器单元测试
 *
 * @author Ateng
 * @since 2026-11-02
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ZombieInstanceCleanupScheduler 僵尸实例自动清理调度器单元测试")
class ZombieInstanceCleanupSchedulerTest {

    @Mock
    private InstanceRepository repository;

    @Mock
    private InstanceRegistry registry;

    private AtengAdminServerProperties properties;
    private ZombieInstanceCleanupScheduler scheduler;

    @BeforeEach
    void setUp() {
        properties = new AtengAdminServerProperties();
        properties.getCleanup().setEnabled(true);
        properties.getCleanup().setInterval(Duration.ofMillis(100));
        properties.getCleanup().setZombieThreshold(Duration.ofMillis(50));

        scheduler = new ZombieInstanceCleanupScheduler(repository, registry, properties);
    }

    private Instance createInstance(String idStr, StatusInfo statusInfo) {
        return createInstance(idStr, statusInfo, Instant.now(), true);
    }

    private Instance createInstance(String idStr, StatusInfo statusInfo, Instant statusTimestamp) {
        return createInstance(idStr, statusInfo, statusTimestamp, true);
    }

    private Instance createInstance(String idStr, StatusInfo statusInfo, Instant statusTimestamp, boolean registered) {
        Instance instance = org.mockito.Mockito.mock(Instance.class);
        InstanceId instanceId = InstanceId.of(idStr);
        Registration registration = Registration.builder()
                .name("test-app-" + idStr)
                .healthUrl("http://127.0.0.1:8080/actuator/health")
                .managementUrl("http://127.0.0.1:8080/actuator")
                .serviceUrl("http://127.0.0.1:8080/")
                .build();
        org.mockito.Mockito.lenient().when(instance.getId()).thenReturn(instanceId);
        org.mockito.Mockito.lenient().when(instance.isRegistered()).thenReturn(registered);
        org.mockito.Mockito.lenient().when(instance.getStatusInfo()).thenReturn(statusInfo);
        org.mockito.Mockito.lenient().when(instance.getStatusTimestamp()).thenReturn(statusTimestamp);
        org.mockito.Mockito.lenient().when(instance.getRegistration()).thenReturn(registration);
        return instance;
    }

    @Test
    @DisplayName("超期且处于 OFFLINE 状态的实例应当被成功驱逐注销")
    void shouldEvictExpiredOfflineInstance() {
        // 构造状态更新时间为 100ms 之前的离线实例（阈值为 50ms），消除物理 Thread.sleep 盲等
        Instance zombieInstance = createInstance("inst-offline", StatusInfo.ofOffline(), Instant.now().minus(Duration.ofMillis(100)));

        when(repository.findAll()).thenReturn(Flux.just(zombieInstance));
        when(registry.deregister(InstanceId.of("inst-offline"))).thenReturn(Mono.just(InstanceId.of("inst-offline")));

        int evicted = scheduler.cleanZombies();

        assertThat(evicted).isEqualTo(1);
        verify(registry, times(1)).deregister(InstanceId.of("inst-offline"));
    }

    @Test
    @DisplayName("超期且处于 UNKNOWN 状态的实例应当被成功驱逐注销")
    void shouldEvictExpiredUnknownInstance() {
        Instance zombieInstance = createInstance("inst-unknown", StatusInfo.ofUnknown(), Instant.now().minus(Duration.ofMillis(100)));

        when(repository.findAll()).thenReturn(Flux.just(zombieInstance));
        when(registry.deregister(InstanceId.of("inst-unknown"))).thenReturn(Mono.just(InstanceId.of("inst-unknown")));

        int evicted = scheduler.cleanZombies();

        assertThat(evicted).isEqualTo(1);
        verify(registry, times(1)).deregister(InstanceId.of("inst-unknown"));
    }

    @Test
    @DisplayName("未超期的 OFFLINE 实例不应被驱逐注销")
    void shouldNotEvictNonExpiredOfflineInstance() {
        // 设置超期阈值为 1 小时，刚创建的实例绝不会超期
        properties.getCleanup().setZombieThreshold(Duration.ofHours(1));
        Instance recentOfflineInstance = createInstance("inst-recent", StatusInfo.ofOffline(), Instant.now());

        when(repository.findAll()).thenReturn(Flux.just(recentOfflineInstance));

        int evicted = scheduler.cleanZombies();

        assertThat(evicted).isZero();
        verify(registry, never()).deregister(any());
    }

    @Test
    @DisplayName("处于健康 UP 状态的实例即使时间超期也绝不应被驱逐注销")
    void shouldNotEvictExpiredUpInstance() {
        Instance upInstance = createInstance("inst-up", StatusInfo.ofUp(), Instant.now().minus(Duration.ofMillis(100)));

        when(repository.findAll()).thenReturn(Flux.just(upInstance));

        int evicted = scheduler.cleanZombies();

        assertThat(evicted).isZero();
        verify(registry, never()).deregister(any());
    }

    @Test
    @DisplayName("未注册或已注销的实例应当被过滤忽略")
    void shouldIgnoreUnregisteredInstance() {
        Instance deregisteredInstance = createInstance("inst-deregistered", StatusInfo.ofOffline(), Instant.now().minus(Duration.ofMillis(100)), false);

        when(repository.findAll()).thenReturn(Flux.just(deregisteredInstance));

        int evicted = scheduler.cleanZombies();

        assertThat(evicted).isZero();
        verify(registry, never()).deregister(any());
    }

    @Test
    @DisplayName("当 InstanceRegistry 为空时能够降级通过 InstanceRepository computeIfPresent 触发注销")
    void shouldFallbackToRepositoryWhenRegistryIsNull() {
        ZombieInstanceCleanupScheduler fallbackScheduler = new ZombieInstanceCleanupScheduler(repository, null, properties);
        Instance zombieInstance = createInstance("inst-fallback", StatusInfo.ofOffline(), Instant.now().minus(Duration.ofMillis(100)));

        when(repository.findAll()).thenReturn(Flux.just(zombieInstance));
        when(repository.computeIfPresent(any(), any())).thenReturn(Mono.just(zombieInstance));

        int evicted = fallbackScheduler.cleanZombies();

        assertThat(evicted).isEqualTo(1);
        verify(repository, times(1)).computeIfPresent(any(), any());
    }

    @Test
    @DisplayName("持久层查询异常时能够优雅降级返回 0 且不抛出次生异常")
    void shouldHandleRepositoryExceptionGracefully() {
        when(repository.findAll()).thenReturn(Flux.error(new RuntimeException("DB Connection Refused")));

        int evicted = scheduler.cleanZombies();

        assertThat(evicted).isZero();
    }

    @Test
    @DisplayName("单实例驱逐异常时能够继续处理后续候选实例")
    void shouldContinueWhenSingleInstanceEvictionFails() {
        Instance failInstance = createInstance("inst-fail", StatusInfo.ofOffline(), Instant.now().minus(Duration.ofMillis(100)));
        Instance successInstance = createInstance("inst-success", StatusInfo.ofOffline(), Instant.now().minus(Duration.ofMillis(100)));

        when(repository.findAll()).thenReturn(Flux.just(failInstance, successInstance));
        when(registry.deregister(InstanceId.of("inst-fail"))).thenReturn(Mono.error(new RuntimeException("Eviction error")));
        when(registry.deregister(InstanceId.of("inst-success"))).thenReturn(Mono.just(InstanceId.of("inst-success")));

        int evicted = scheduler.cleanZombies();

        assertThat(evicted).isEqualTo(1);
        verify(registry, times(1)).deregister(InstanceId.of("inst-fail"));
        verify(registry, times(1)).deregister(InstanceId.of("inst-success"));
    }

    @Test
    @DisplayName("生命周期 start 与 stop 能够正确启动和优雅停止调度线程")
    void shouldManageLifecycleStartAndStopCorrectly() {
        assertThat(scheduler.isRunning()).isFalse();
        assertThat(scheduler.isAutoStartup()).isTrue();
        assertThat(scheduler.getPhase()).isEqualTo(Integer.MAX_VALUE - 100);

        scheduler.start();
        assertThat(scheduler.isRunning()).isTrue();

        // 重复调用 start 具备幂等性
        scheduler.start();
        assertThat(scheduler.isRunning()).isTrue();

        AtomicBoolean callbackRan = new AtomicBoolean(false);
        scheduler.stop(() -> callbackRan.set(true));
        assertThat(scheduler.isRunning()).isFalse();
        assertThat(callbackRan.get()).isTrue();
    }

    @Test
    @DisplayName("配置 cleanup.enabled=false 时 start() 保持静默不启动调度器")
    void shouldNotStartWhenCleanupDisabled() {
        properties.getCleanup().setEnabled(false);

        scheduler.start();

        assertThat(scheduler.isRunning()).isFalse();
    }
}
