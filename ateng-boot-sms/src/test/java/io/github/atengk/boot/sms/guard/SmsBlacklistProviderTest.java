package io.github.atengk.boot.sms.guard;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 短信出站黑名单过滤器对称性与异常对抗性单测
 *
 * @author Ateng
 * @since 2026-11-07
 */
@ExtendWith(MockitoExtension.class)
class SmsBlacklistProviderTest {

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private HashOperations<String, Object, Object> hashOperations;

    @Test
    @DisplayName("InMemorySmsBlacklistProvider: 基础增删查清空与对称性验证")
    void testInMemoryBlacklistSymmetric() {
        InMemorySmsBlacklistProvider provider = new InMemorySmsBlacklistProvider(List.of("13800000001"));

        assertThat(provider.isBlacklisted("13800000001")).isTrue();
        assertThat(provider.size()).isEqualTo(1);
        assertThat(provider.getReason("13800000001")).contains("出站安全风控黑名单");

        // 动态单条添加
        provider.add("13800000002", "用户显式要求加入黑名单");
        assertThat(provider.isBlacklisted("13800000002")).isTrue();
        assertThat(provider.getReason("13800000002")).isEqualTo("用户显式要求加入黑名单");
        assertThat(provider.size()).isEqualTo(2);

        // 动态批量添加集合
        provider.addAll(List.of("13800000003", "13800000004"));
        assertThat(provider.size()).isEqualTo(4);
        assertThat(provider.isBlacklisted("13800000003")).isTrue();
        assertThat(provider.isBlacklisted("13800000004")).isTrue();

        // 全量查询只读镜像
        Set<String> allBlacklist = provider.getBlacklist();
        assertThat(allBlacklist).contains("13800000001", "13800000002", "13800000003", "13800000004");
        assertThatThrownBy(() -> allBlacklist.add("13899999999"))
                .isInstanceOf(UnsupportedOperationException.class);

        // 单条移除与批量移除
        provider.remove("13800000001");
        assertThat(provider.isBlacklisted("13800000001")).isFalse();
        assertThat(provider.size()).isEqualTo(3);

        provider.removeAll(List.of("13800000002", "13800000003"));
        assertThat(provider.size()).isEqualTo(1);
        assertThat(provider.isBlacklisted("13800000002")).isFalse();

        // 清空
        provider.clear();
        assertThat(provider.size()).isZero();
        assertThat(provider.getBlacklist()).isEmpty();
    }

    @Test
    @DisplayName("InMemorySmsBlacklistProvider: 极限与对抗性边界防御")
    void testInMemoryAdversarialInputs() {
        InMemorySmsBlacklistProvider provider = new InMemorySmsBlacklistProvider();

        assertThat(provider.isBlacklisted(null)).isFalse();
        assertThat(provider.isBlacklisted("")).isFalse();
        assertThat(provider.isBlacklisted("   ")).isFalse();
        assertThat(provider.getReason(null)).isEqualTo("目标手机号已退订或处于出站安全风控黑名单");

        // 空入参安全放行
        provider.add(null);
        provider.add("  ", "reason");
        provider.addAll((Collection<String>) null);
        provider.remove(null);
        provider.removeAll(null);
        assertThat(provider.size()).isZero();
    }

    @Test
    @DisplayName("RedisSmsBlacklistProvider: Redis 分布式 Hash 增删查与初始配置合并")
    void testRedisBlacklistProvider() {
        when(stringRedisTemplate.opsForHash()).thenReturn(hashOperations);

        // 构造器启动合并
        RedisSmsBlacklistProvider provider = new RedisSmsBlacklistProvider(stringRedisTemplate, List.of("13800000001"));
        verify(hashOperations, times(1)).putAll(eq("ateng:sms:blacklist"), anyMap());

        // 判定黑名单命中
        when(hashOperations.hasKey("ateng:sms:blacklist", "13800000001")).thenReturn(true);
        assertThat(provider.isBlacklisted("13800000001")).isTrue();

        // 判定未命中
        when(hashOperations.hasKey("ateng:sms:blacklist", "13800000099")).thenReturn(false);
        assertThat(provider.isBlacklisted("13800000099")).isFalse();

        // 获取原因
        when(hashOperations.get("ateng:sms:blacklist", "13800000001")).thenReturn("退订拦截");
        assertThat(provider.getReason("13800000001")).isEqualTo("退订拦截");

        // 移除单条与批量
        provider.remove("13800000001");
        verify(hashOperations, times(1)).delete("ateng:sms:blacklist", "13800000001");

        provider.removeAll(List.of("13800000002", "13800000003"));
        verify(hashOperations, times(1)).delete("ateng:sms:blacklist", "13800000002", "13800000003");

        // 获取全量黑名单
        when(hashOperations.keys("ateng:sms:blacklist")).thenReturn(Set.of("13800000001", "13800000002"));
        assertThat(provider.getBlacklist()).containsExactlyInAnyOrder("13800000001", "13800000002");

        // 大小与清空
        when(hashOperations.size("ateng:sms:blacklist")).thenReturn(2L);
        assertThat(provider.size()).isEqualTo(2);

        provider.clear();
        verify(stringRedisTemplate, times(1)).delete("ateng:sms:blacklist");
    }

    @Test
    @DisplayName("RedisSmsBlacklistProvider: Redis 异常时弹性降级保底防御")
    void testRedisBlacklistExceptionResilience() {
        when(stringRedisTemplate.opsForHash()).thenThrow(new RuntimeException("Redis connection refused"));

        RedisSmsBlacklistProvider provider = new RedisSmsBlacklistProvider(stringRedisTemplate);

        // Redis 挂掉时，判定黑名单保底返回 false，杜绝阻塞正常发信
        assertThat(provider.isBlacklisted("13800000001")).isFalse();
        assertThat(provider.getReason("13800000001")).isEqualTo("目标手机号已退订或处于出站安全风控黑名单");
        assertThat(provider.getBlacklist()).isEmpty();
        assertThat(provider.size()).isZero();
    }
}
