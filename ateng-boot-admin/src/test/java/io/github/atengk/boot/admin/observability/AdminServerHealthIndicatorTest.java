package io.github.atengk.boot.admin.observability;

import de.codecentric.boot.admin.server.domain.entities.EventsourcingInstanceRepository;
import de.codecentric.boot.admin.server.domain.entities.Instance;
import de.codecentric.boot.admin.server.domain.entities.InstanceRepository;
import de.codecentric.boot.admin.server.domain.values.InstanceId;
import de.codecentric.boot.admin.server.domain.values.Registration;
import de.codecentric.boot.admin.server.domain.values.StatusInfo;
import de.codecentric.boot.admin.server.eventstore.InMemoryEventStore;
import io.github.atengk.boot.admin.notifier.AtengAdminNotificationNotifier;
import io.github.atengk.boot.admin.properties.AtengAdminServerProperties;
import io.github.atengk.boot.admin.scheduler.ZombieInstanceCleanupScheduler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * AdminServerHealthIndicator 监控中心自检健康探针单元测试
 *
 * @author Ateng
 * @since 2026-11-21
 */
@DisplayName("AdminServerHealthIndicator 服务端自检探针单元测试")
class AdminServerHealthIndicatorTest {

    @Test
    @DisplayName("通知器与调度器运行正常时返回 UP 并输出注册表摘要；停止或仓库异常时汇报 DOWN")
    void shouldReportUpWhenHealthyAndDownWhenComponentStoppedOrRepoFails() {
        InstanceRepository repository = new EventsourcingInstanceRepository(new InMemoryEventStore());
        Instance upInst = Instance.create(InstanceId.of("inst-01"))
                .register(Registration.create("order-svc", "http://127.0.0.1:8080/actuator/health").build());
        repository.save(upInst).block();
        repository.compute(InstanceId.of("inst-01"), (id, curr) -> Mono.just(curr.withStatusInfo(StatusInfo.ofUp()))).block();

        AtengAdminServerProperties properties = new AtengAdminServerProperties();
        properties.getCleanup().setEnabled(true);

        AtengAdminNotificationNotifier notifier = new AtengAdminNotificationNotifier(repository, null, properties);
        ZombieInstanceCleanupScheduler cleanupScheduler = new ZombieInstanceCleanupScheduler(repository, null, properties);
        notifier.start();
        cleanupScheduler.start();
        try {
            AdminServerHealthIndicator indicator = new AdminServerHealthIndicator(
                    repository, notifier, cleanupScheduler, null, properties
            );
            Health healthy = indicator.health();
            assertThat(healthy.getStatus()).isEqualTo(Status.UP);
            assertThat(healthy.getDetails())
                    .containsEntry("registeredInstances", 1)
                    .containsEntry("upInstances", 1)
                    .containsEntry("notifierRunning", true)
                    .containsEntry("cleanupEnabled", true)
                    .containsEntry("cleanupRunning", true);

            // 停止清理调度器但 cleanup.enabled=true -> DOWN
            cleanupScheduler.stop();
            Health degraded = indicator.health();
            assertThat(degraded.getStatus()).isEqualTo(Status.DOWN);

            // 停止通知器 -> DOWN
            notifier.stop();
            assertThat(indicator.health().getStatus()).isEqualTo(Status.DOWN);
        } finally {
            cleanupScheduler.stop();
            notifier.stop();
        }

        // 仓库异常 -> DOWN
        InstanceRepository brokenRepo = mock(InstanceRepository.class);
        when(brokenRepo.findAll()).thenReturn(Flux.error(new IllegalStateException("repo corrupted")));
        AdminServerHealthIndicator brokenIndicator = new AdminServerHealthIndicator(brokenRepo, null, null, null, properties);
        Health down = brokenIndicator.health();
        assertThat(down.getStatus()).isEqualTo(Status.DOWN);
    }
}
