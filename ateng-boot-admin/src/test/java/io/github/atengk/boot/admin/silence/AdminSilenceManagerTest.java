package io.github.atengk.boot.admin.silence;

import io.github.atengk.boot.admin.cluster.InMemoryAdminClusterCoordinator;
import io.github.atengk.boot.core.exception.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * AdminSilenceManager 运行时动态维护静默窗口单元测试
 *
 * @author Ateng
 * @since 2026-11-21
 */
@DisplayName("AdminSilenceManager 维护静默窗口单元测试")
class AdminSilenceManagerTest {

    @Test
    @DisplayName("支持按服务通配符创建、列举、到期自动失效、撤销与非法入参 Fail-Fast")
    void shouldManageMaintenanceSilenceRulesDeterministically() {
        AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-11-21T09:00:00Z"));
        Clock clock = new Clock() {
            @Override
            public ZoneOffset getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(java.time.ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return now.get();
            }
        };

        InMemoryAdminClusterCoordinator coordinator = new InMemoryAdminClusterCoordinator(clock);
        DefaultAdminSilenceManager silenceManager = new DefaultAdminSilenceManager(coordinator, Duration.ofHours(2), clock);

        // 1. 非法入参 Fail-Fast 抛出 BusinessException
        assertThatThrownBy(() -> silenceManager.createSilence("  ", Duration.ofMinutes(30), "变更", "admin"))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> silenceManager.createSilence("order-*", Duration.ofSeconds(-5), "变更", "admin"))
                .isInstanceOf(BusinessException.class);

        // 2. 创建维护静默规则（精确/通配符匹配）
        SilenceRule orderRule = silenceManager.createSilence("order-*", Duration.ofMinutes(30), "灰度发布", "ops-zhang");
        assertThat(orderRule.getId()).isNotBlank();
        assertThat(silenceManager.isSilenced("order-core-service")).isTrue();
        assertThat(silenceManager.isSilenced("pay-service")).isFalse();
        assertThat(silenceManager.findMatchingRule("order-core-service"))
                .isPresent()
                .get()
                .extracting(SilenceRule::getReason)
                .isEqualTo("灰度发布");
        assertThat(silenceManager.listActiveSilences()).hasSize(1);

        // 3. 推进时钟越过 TTL -> 自动过期失效
        now.set(now.get().plus(Duration.ofMinutes(31)));
        assertThat(silenceManager.isSilenced("order-core-service")).isFalse();
        assertThat(silenceManager.listActiveSilences()).isEmpty();

        // 4. 创建未传显式时长时回退默认 TTL，且支持显式撤销与 cleanExpiredSilences 精确统计清理条数
        SilenceRule payRule = silenceManager.createSilence("pay-*", null, "数据库割接", "dba-li");
        assertThat(payRule.getExpiresAt()).isEqualTo(now.get().plus(Duration.ofHours(2)));
        assertThat(silenceManager.isSilenced("pay-gateway")).isTrue();
        assertThat(silenceManager.revokeSilence(payRule.getId())).isTrue();
        assertThat(silenceManager.isSilenced("pay-gateway")).isFalse();
        assertThat(silenceManager.revokeSilence(payRule.getId())).isFalse();

        // 5. cleanExpiredSilences 精确返回并清除本次过期规则数
        silenceManager.createSilence("batch-a-*", Duration.ofMinutes(5), "清理测试A", "ops");
        silenceManager.createSilence("batch-b-*", Duration.ofMinutes(5), "清理测试B", "ops");
        now.set(now.get().plus(Duration.ofMinutes(6)));
        assertThat(silenceManager.cleanExpiredSilences()).isEqualTo(2);
        assertThat(silenceManager.cleanExpiredSilences()).isZero();
        assertThat(silenceManager.listActiveSilences()).isEmpty();
    }
}
