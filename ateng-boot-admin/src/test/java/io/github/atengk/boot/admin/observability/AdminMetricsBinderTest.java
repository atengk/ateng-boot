package io.github.atengk.boot.admin.observability;

import de.codecentric.boot.admin.server.domain.entities.EventsourcingInstanceRepository;
import de.codecentric.boot.admin.server.domain.entities.Instance;
import de.codecentric.boot.admin.server.domain.entities.InstanceRepository;
import de.codecentric.boot.admin.server.domain.values.InstanceId;
import de.codecentric.boot.admin.server.domain.values.Registration;
import de.codecentric.boot.admin.server.domain.values.StatusInfo;
import de.codecentric.boot.admin.server.eventstore.InMemoryEventStore;
import io.github.atengk.boot.admin.cluster.InMemoryAdminClusterCoordinator;
import io.github.atengk.boot.admin.notifier.AtengAdminNotificationNotifier;
import io.github.atengk.boot.admin.properties.AtengAdminServerProperties;
import io.github.atengk.boot.admin.silence.AdminSilenceManager;
import io.github.atengk.boot.admin.silence.DefaultAdminSilenceManager;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AdminMetricsBinder 服务端低基数 Micrometer 指标采集单元测试
 *
 * @author Ateng
 * @since 2026-11-21
 */
@DisplayName("AdminMetricsBinder 服务端低基数自观测指标单元测试")
class AdminMetricsBinderTest {

    @Test
    @DisplayName("绑定 MeterRegistry 后准确采集实例状态分布与全链路计数，且严格禁止高基数动态标签")
    void shouldRegisterLowCardinalityMetricsWithoutDynamicTags() {
        InstanceRepository repository = new EventsourcingInstanceRepository(new InMemoryEventStore());
        Instance upInst = Instance.create(InstanceId.of("inst-up-01"))
                .register(Registration.create("order-service", "http://127.0.0.1:8081/actuator/health").build());
        repository.save(upInst).block();
        repository.compute(InstanceId.of("inst-up-01"), (id, curr) -> Mono.just(curr.withStatusInfo(StatusInfo.ofUp()))).block();

        Instance downInst = Instance.create(InstanceId.of("inst-down-01"))
                .register(Registration.create("pay-service", "http://127.0.0.1:8082/actuator/health").build());
        repository.save(downInst).block();
        repository.compute(InstanceId.of("inst-down-01"), (id, curr) -> Mono.just(curr.withStatusInfo(StatusInfo.ofDown()))).block();

        AtengAdminServerProperties properties = new AtengAdminServerProperties();
        AdminSilenceManager silenceManager = new DefaultAdminSilenceManager(new InMemoryAdminClusterCoordinator(), Duration.ofHours(1));
        silenceManager.createSilence("pay-*", Duration.ofMinutes(10), "维护中", "ops");

        AtengAdminNotificationNotifier notifier = new AtengAdminNotificationNotifier(repository, null, properties);
        notifier.start();
        try {
            AdminMetricsBinder binder = new AdminMetricsBinder(repository, notifier, silenceManager);
            SimpleMeterRegistry registry = new SimpleMeterRegistry();
            binder.bindTo(registry);

            binder.recordAdmission(true);
            binder.recordAdmission(true);
            binder.recordAdmission(false);
            binder.recordLogin("success");
            binder.recordLogin("failure");
            binder.recordLogin("locked");
            binder.recordEndpointBlocked();
            binder.recordAlert("debounced");
            binder.recordAlert("sent");
            binder.recordAlert("reminder");
            binder.recordAlert("storm_aggregated");
            binder.recordAlert("silenced");
            binder.recordZombieEvicted(3);

            assertThat(registry.get("ateng.admin.instances.registered").gauge().value()).isEqualTo(2.0d);
            assertThat(registry.get("ateng.admin.instances").tag("status", "UP").gauge().value()).isEqualTo(1.0d);
            assertThat(registry.get("ateng.admin.instances").tag("status", "DOWN").gauge().value()).isEqualTo(1.0d);
            assertThat(registry.get("ateng.admin.silences.active").gauge().value()).isEqualTo(1.0d);

            assertThat(registry.get("ateng.admin.admission.requests").tag("outcome", "admitted").functionCounter().count()).isEqualTo(2.0d);
            assertThat(registry.get("ateng.admin.admission.requests").tag("outcome", "rejected").functionCounter().count()).isEqualTo(1.0d);
            assertThat(registry.get("ateng.admin.login.attempts").tag("outcome", "success").functionCounter().count()).isEqualTo(1.0d);
            assertThat(registry.get("ateng.admin.login.attempts").tag("outcome", "failure").functionCounter().count()).isEqualTo(1.0d);
            assertThat(registry.get("ateng.admin.login.attempts").tag("outcome", "locked").functionCounter().count()).isEqualTo(1.0d);
            assertThat(registry.get("ateng.admin.endpoint.blocked.total").functionCounter().count()).isEqualTo(1.0d);
            assertThat(registry.get("ateng.admin.alerts").tag("type", "debounced").functionCounter().count()).isEqualTo(1.0d);
            assertThat(registry.get("ateng.admin.alerts").tag("type", "sent").functionCounter().count()).isEqualTo(1.0d);
            assertThat(registry.get("ateng.admin.alerts").tag("type", "reminder").functionCounter().count()).isEqualTo(1.0d);
            assertThat(registry.get("ateng.admin.alerts").tag("type", "storm_aggregated").functionCounter().count()).isEqualTo(1.0d);
            assertThat(registry.get("ateng.admin.alerts").tag("type", "silenced").functionCounter().count()).isEqualTo(1.0d);
            assertThat(registry.get("ateng.admin.zombies.evicted.total").functionCounter().count()).isEqualTo(3.0d);

            // 严格验证禁止包含高基数动态 Tag
            Set<String> forbiddenTags = Set.of("instanceId", "instance", "url", "uri", "serviceName", "actor", "ip");
            for (Meter meter : registry.getMeters()) {
                for (Tag tag : meter.getId().getTags()) {
                    assertThat(forbiddenTags).doesNotContain(tag.getKey());
                }
            }
        } finally {
            notifier.stop();
        }
    }
}
