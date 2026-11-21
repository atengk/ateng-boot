package io.github.atengk.boot.admin.eventstore;

import de.codecentric.boot.admin.server.domain.events.InstanceDeregisteredEvent;
import de.codecentric.boot.admin.server.domain.events.InstanceRegisteredEvent;
import de.codecentric.boot.admin.server.domain.values.InstanceId;
import de.codecentric.boot.admin.server.domain.values.Registration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AtengInMemoryEventStore 有界内存事件仓库单元测试
 *
 * @author Ateng
 * @since 2026-11-21
 */
@DisplayName("AtengInMemoryEventStore 有界内存事件仓库单元测试")
class AtengInMemoryEventStoreTest {

    @Test
    @DisplayName("超出 maxRetainedDeregistered 上限时按 FIFO 淘汰最旧已注销实例事件，保护活跃实例并支持显式 evict")
    void shouldEvictOldestDeregisteredEventsWhenExceedingMaxRetained() {
        AtengInMemoryEventStore store = new AtengInMemoryEventStore(100, 2);

        InstanceId activeId = InstanceId.of("active-01");
        InstanceId d1 = InstanceId.of("dereg-01");
        InstanceId d2 = InstanceId.of("dereg-02");
        InstanceId d3 = InstanceId.of("dereg-03");

        Instant t0 = Instant.parse("2026-11-21T08:00:00Z");
        Registration reg = Registration.create("demo-svc", "http://10.20.1.5:8080/actuator/health").build();

        // 注册 4 个实例
        store.append(List.of(new InstanceRegisteredEvent(activeId, 0L, t0, reg))).block();
        store.append(List.of(new InstanceRegisteredEvent(d1, 0L, t0.plusSeconds(1), reg))).block();
        store.append(List.of(new InstanceRegisteredEvent(d2, 0L, t0.plusSeconds(2), reg))).block();
        store.append(List.of(new InstanceRegisteredEvent(d3, 0L, t0.plusSeconds(3), reg))).block();

        // 注销 d1, d2 -> 保留上限为 2，两者均保留
        store.append(List.of(new InstanceDeregisteredEvent(d1, 1L, t0.plusSeconds(10)))).block();
        store.append(List.of(new InstanceDeregisteredEvent(d2, 1L, t0.plusSeconds(11)))).block();
        assertThat(store.find(d1).collectList().block()).hasSize(2);
        assertThat(store.find(d2).collectList().block()).hasSize(2);

        // 注销第 3 个 d3 -> 触发 FIFO 淘汰最旧的 d1
        store.append(List.of(new InstanceDeregisteredEvent(d3, 1L, t0.plusSeconds(12)))).block();
        assertThat(store.find(d1).collectList().block()).isEmpty();
        assertThat(store.find(d2).collectList().block()).hasSize(2);
        assertThat(store.find(d3).collectList().block()).hasSize(2);
        assertThat(store.find(activeId).collectList().block()).hasSize(1);

        // 显式驱逐 d2 后立即释放事件历史
        store.evict(d2);
        assertThat(store.find(d2).collectList().block()).isEmpty();
        assertThat(store.getStoredInstanceCount()).isEqualTo(2);

        // d2 重新上线注册后再注销第 4 个实例不应误删活跃 d2
        store.append(List.of(new InstanceRegisteredEvent(d2, 0L, t0.plusSeconds(20), reg))).block();
        InstanceId d4 = InstanceId.of("dereg-04");
        store.append(List.of(new InstanceRegisteredEvent(d4, 0L, t0.plusSeconds(21), reg))).block();
        store.append(List.of(new InstanceDeregisteredEvent(d4, 1L, t0.plusSeconds(22)))).block();
        assertThat(store.find(d2).collectList().block()).hasSize(1);
    }
}
