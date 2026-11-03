package io.github.atengk.boot.social.cache;

import io.github.atengk.boot.social.model.StateContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * InMemorySocialAuthStateCache 单机内存状态缓存对抗性测试
 *
 * @author Ateng
 * @since 2026-11-03
 */
class InMemorySocialAuthStateCacheTest {

    private InMemorySocialAuthStateCache cache;

    @BeforeEach
    void setUp() {
        cache = new InMemorySocialAuthStateCache();
    }

    @Test
    @DisplayName("验证 State 生成、存储与结构化 StateContext 还原")
    void testGenerateStateAndGetContext() {
        StateContext context = StateContext.builder()
                .tenantId("tenant_001")
                .redirectUrl("https://app.example.com/welcome")
                .extraParams(Map.of("sourceChannel", "h5_banner"))
                .build();

        String state = cache.generateState(context);
        assertThat(state).isNotBlank().hasSize(32);
        assertThat(cache.contains(state)).isTrue();

        StateContext restored = cache.getContext(state);
        assertThat(restored).isNotNull();
        assertThat(restored.getTenantId()).isEqualTo("tenant_001");
        assertThat(restored.getRedirectUrl()).isEqualTo("https://app.example.com/welcome");
        assertThat(restored.getExtraParams()).containsEntry("sourceChannel", "h5_banner");
    }

    @Test
    @DisplayName("验证原子消费与阅后即焚防重放（第二次提取必为 null）")
    void testRemoveContext_BurnAfterReading() {
        StateContext context = StateContext.builder()
                .tenantId("tenant_burn")
                .redirectUrl("https://app.example.com/deep-link")
                .build();

        String state = cache.generateState(context);

        // 第一次消费，成功提取并从缓存中物理删除
        StateContext firstConsume = cache.removeContext(state);
        assertThat(firstConsume).isNotNull();
        assertThat(firstConsume.getRedirectUrl()).isEqualTo("https://app.example.com/deep-link");

        // 验证已从缓存中彻底移除
        assertThat(cache.contains(state)).isFalse();

        // 第二次重复消费（模拟攻击者重放拦截的 OAuth2 回调链接），必返回 null
        StateContext secondConsume = cache.removeContext(state);
        assertThat(secondConsume).isNull();
    }

    @Test
    @DisplayName("验证 Anduin 原生 AuthStateCache 接口兼容适配")
    void testAnduinInterfaceAdaptation() {
        String key = "anduin_state_key";
        String value = "https://example.com/redirect";

        cache.cache(key, value);
        assertThat(cache.containsKey(key)).isTrue();
        assertThat(cache.get(key)).isEqualTo(value);

        cache.expire(key);
        assertThat(cache.containsKey(key)).isFalse();
    }

    @Test
    @DisplayName("验证空边界防御：null 或空串安全防御不崩")
    void testNullSafety() {
        cache.set(null, null, Duration.ofMinutes(1));
        assertThat(cache.getContext(null)).isNull();
        assertThat(cache.getContext("")).isNull();
        assertThat(cache.removeContext(null)).isNull();
        assertThat(cache.removeContext("")).isNull();
        assertThat(cache.contains(null)).isFalse();
    }
}
