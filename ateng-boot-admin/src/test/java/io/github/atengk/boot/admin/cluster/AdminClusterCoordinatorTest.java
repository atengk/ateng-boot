package io.github.atengk.boot.admin.cluster;

import io.github.atengk.boot.admin.silence.SilenceRule;
import io.github.atengk.boot.core.util.JsonUtil;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AdminClusterCoordinator 单机与 Fail-Open Redis 协调器单元测试
 *
 * @author Ateng
 * @since 2026-11-21
 */
@DisplayName("AdminClusterCoordinator 集群协调器单元测试")
class AdminClusterCoordinatorTest {

    @Test
    @DisplayName("单机内存协调器：支持互斥锁、防重标记按 Clock TTL 过期与误释放保护")
    void shouldCoordinateLocksDedupAndSilenceRulesInMemory() {
        AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-11-21T08:00:00Z"));
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

        // 1. 互斥锁争用与持有者隔离释放
        assertThat(coordinator.tryAcquireLock("cleanup", "node-1", Duration.ofSeconds(30))).isTrue();
        assertThat(coordinator.tryAcquireLock("cleanup", "node-2", Duration.ofSeconds(30))).isFalse();

        // 非持锁节点释放无效
        coordinator.releaseLock("cleanup", "node-2");
        assertThat(coordinator.tryAcquireLock("cleanup", "node-2", Duration.ofSeconds(30))).isFalse();

        // 持锁节点释放后可再次获取
        coordinator.releaseLock("cleanup", "node-1");
        assertThat(coordinator.tryAcquireLock("cleanup", "node-2", Duration.ofSeconds(30))).isTrue();

        // 2. TTL 到期自动失效可抢占
        now.set(now.get().plusSeconds(31));
        assertThat(coordinator.tryAcquireLock("cleanup", "node-1", Duration.ofSeconds(30))).isTrue();

        // 3. 告警去重窗口防重发
        assertThat(coordinator.tryMarkAlertDispatched("inst-1:down:1", Duration.ofSeconds(60))).isTrue();
        assertThat(coordinator.tryMarkAlertDispatched("inst-1:down:1", Duration.ofSeconds(60))).isFalse();
        now.set(now.get().plusSeconds(61));
        assertThat(coordinator.tryMarkAlertDispatched("inst-1:down:1", Duration.ofSeconds(60))).isTrue();

        // 4. 静默规则存储与过期剔除
        SilenceRule activeRule = SilenceRule.builder()
                .id("rule-active")
                .servicePattern("order-*")
                .reason("发布演练")
                .createdAt(now.get())
                .expiresAt(now.get().plusSeconds(120))
                .build();
        SilenceRule expiredRule = SilenceRule.builder()
                .id("rule-expired")
                .servicePattern("pay-*")
                .reason("已过期")
                .createdAt(now.get().minusSeconds(60))
                .expiresAt(now.get().minusSeconds(1))
                .build();
        coordinator.saveSilenceRule(activeRule);
        coordinator.saveSilenceRule(expiredRule);
        assertThat(coordinator.listSilenceRules()).extracting(SilenceRule::getId).containsExactly("rule-active");

        coordinator.deleteSilenceRule("rule-active");
        assertThat(coordinator.listSilenceRules()).isEmpty();
    }

    @Test
    @DisplayName("Redis 协调器：正常执行 SETNX 互斥与 Lua 释放，遭遇 Redis 连接/超时异常时严格 Fail-Open 放行不中断流程")
    @SuppressWarnings("unchecked")
    void shouldFailOpenGracefullyWhenRedisErrorsOccur() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOps = mock(ValueOperations.class);
        HashOperations<String, Object, Object> hashOps = mock(HashOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(redisTemplate.opsForHash()).thenReturn(hashOps);

        AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-11-21T08:00:00Z"));
        Clock clock = Clock.fixed(now.get(), ZoneOffset.UTC);

        RedisAdminClusterCoordinator coordinator = new RedisAdminClusterCoordinator(
                redisTemplate, "ateng:admin:", clock);

        // 1. 正常争用：首个节点 SETNX 返回 true，第二个节点返回 false
        when(valueOps.setIfAbsent(eq("ateng:admin:lock:zombie-cleanup"), eq("node-a"), eq(Duration.ofSeconds(30))))
                .thenReturn(true);
        when(valueOps.setIfAbsent(eq("ateng:admin:lock:zombie-cleanup"), eq("node-b"), eq(Duration.ofSeconds(30))))
                .thenReturn(false);
        assertThat(coordinator.tryAcquireLock("zombie-cleanup", "node-a", Duration.ofSeconds(30))).isTrue();
        assertThat(coordinator.tryAcquireLock("zombie-cleanup", "node-b", Duration.ofSeconds(30))).isFalse();

        // 释放锁通过 Lua 脚本原子执行
        coordinator.releaseLock("zombie-cleanup", "node-a");
        verify(redisTemplate).execute(any(RedisScript.class), anyList(), eq("node-a"));

        // 2. 注入 RedisConnectionFailureException / QueryTimeoutException -> Fail-Open 放行，绝不抛出异常
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenThrow(new RedisConnectionFailureException("Simulated Redis down"));
        assertThat(coordinator.tryAcquireLock("zombie-cleanup", "node-a", Duration.ofSeconds(30))).isTrue();
        assertThat(coordinator.tryMarkAlertDispatched("inst-1:down:1", Duration.ofSeconds(60))).isTrue();

        doThrow(new QueryTimeoutException("Simulated timeout"))
                .when(redisTemplate).execute(any(RedisScript.class), anyList(), any());
        coordinator.releaseLock("zombie-cleanup", "node-a");

        // 静默规则同步异常降级本地缓存兜底，不阻断功能
        doThrow(new RedisConnectionFailureException("Simulated Redis down"))
                .when(hashOps).put(anyString(), any(), any());
        when(hashOps.entries(anyString())).thenThrow(new RedisConnectionFailureException("Simulated Redis down"));

        SilenceRule rule = SilenceRule.builder()
                .id("fallback-rule")
                .servicePattern("order-*")
                .reason("容灾测试")
                .createdAt(now.get())
                .expiresAt(now.get().plusSeconds(300))
                .build();
        coordinator.saveSilenceRule(rule);
        List<SilenceRule> rules = coordinator.listSilenceRules();
        assertThat(rules).extracting(SilenceRule::getId).contains("fallback-rule");

        // deleteSilenceRule 在 Redis 异常时 Fail-Open 同步移除本机缓存且不抛异常
        doThrow(new RedisConnectionFailureException("Simulated Redis down"))
                .when(hashOps).delete(anyString(), any());
        coordinator.deleteSilenceRule("fallback-rule");
        assertThat(coordinator.listSilenceRules()).isEmpty();

        // 3. Redis 恢复正常：listSilenceRules 读取有效规则并批量清理过期记录
        SilenceRule expiredRedisRule = SilenceRule.builder()
                .id("expired-redis")
                .servicePattern("pay-*")
                .createdAt(now.get().minusSeconds(60))
                .expiresAt(now.get().minusSeconds(1))
                .build();
        doReturn(1L).when(hashOps).delete(anyString(), any());
        doReturn(Map.of(
                "fallback-rule", JsonUtil.toJsonString(rule),
                "expired-redis", JsonUtil.toJsonString(expiredRedisRule)
        )).when(hashOps).entries("ateng:admin:silence:rules");
        assertThat(coordinator.listSilenceRules()).extracting(SilenceRule::getId).containsExactly("fallback-rule");
        verify(hashOps).delete(eq("ateng:admin:silence:rules"), eq("expired-redis"));
        verify(redisTemplate, never()).delete(anyString());
    }
}
