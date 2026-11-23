package io.github.atengk.boot.redis.properties;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RedisProperties 全局键前缀幂等合成规则单元测试
 *
 * @author Ateng
 * @since 2026-11-13
 */
@DisplayName("RedisProperties 单元测试")
class RedisPropertiesTest {

    @Test
    @DisplayName("normalizePrefix: 空白返回空串，非空去除首尾空格并自动补齐末尾冒号")
    void testNormalizePrefix() {
        assertThat(RedisProperties.normalizePrefix(null)).isEmpty();
        assertThat(RedisProperties.normalizePrefix("")).isEmpty();
        assertThat(RedisProperties.normalizePrefix("   ")).isEmpty();
        assertThat(RedisProperties.normalizePrefix("prod")).isEqualTo("prod:");
        assertThat(RedisProperties.normalizePrefix("  prod  ")).isEqualTo("prod:");
        assertThat(RedisProperties.normalizePrefix("prod:")).isEqualTo("prod:");
        assertThat(RedisProperties.normalizePrefix("  prod:  ")).isEqualTo("prod:");
    }

    @Test
    @DisplayName("buildKey(rawKey): 覆盖空前缀、带冒号、不带冒号、幂等防重与近似前缀边界")
    void testBuildKeySingleArg() {
        RedisProperties properties = new RedisProperties();

        // 1. null 键透传与空前缀
        assertThat(properties.buildKey(null)).isNull();
        assertThat(properties.buildKey("order:1")).isEqualTo("order:1");

        // 2. 不带冒号前缀自动补齐 ":"
        properties.setKeyPrefix("prod");
        assertThat(properties.buildKey("order:1")).isEqualTo("prod:order:1");
        assertThat(properties.buildKey("prod:order:1")).isEqualTo("prod:order:1");
        // 近似词 "product:1" 不应被 "prod" 误判为已含前缀
        assertThat(properties.buildKey("product:1")).isEqualTo("prod:product:1");

        // 3. 带冒号与空白前缀规范化
        properties.setKeyPrefix("  app:  ");
        assertThat(properties.buildKey("user:100")).isEqualTo("app:user:100");
        assertThat(properties.buildKey("app:user:100")).isEqualTo("app:user:100");
        assertThat(properties.buildKey("sys-dict::")).isEqualTo("app:sys-dict::");
    }

    @Test
    @DisplayName("buildKey(modulePrefix, rawKey): 覆盖空前缀、带冒号/不带冒号、已含全局前缀与已含模块前缀组合")
    void testBuildKeyWithModulePrefix() {
        RedisProperties properties = new RedisProperties();

        // 1. 全局前缀为空 + 模块前缀无冒号
        assertThat(properties.buildKey("lock", "order:1")).isEqualTo("lock:order:1");
        assertThat(properties.buildKey("lock", "lock:order:1")).isEqualTo("lock:order:1");
        assertThat(properties.buildKey("", "order:1")).isEqualTo("order:1");

        // 2. 全局前缀无冒号 + 模块前缀无冒号
        properties.setKeyPrefix("prod");
        assertThat(properties.buildKey("lock", "order:1")).isEqualTo("prod:lock:order:1");
        // 已含模块前缀：前置补齐全局前缀且不重复叠加模块前缀
        assertThat(properties.buildKey("lock", "lock:order:1")).isEqualTo("prod:lock:order:1");
        // 已含完整全局+模块前缀：幂等返回
        assertThat(properties.buildKey("lock:", "prod:lock:order:1")).isEqualTo("prod:lock:order:1");
        // 已含全局前缀但未含模块前缀：保留全局命名空间且保持模块隔离，不叠加双份全局前缀
        assertThat(properties.buildKey("lock:", "prod:order:1")).isEqualTo("prod:lock:order:1");

        // 3. 全局前缀带冒号 + 多层模块前缀
        properties.setKeyPrefix("sample:");
        assertThat(properties.buildKey("lock:cache:", "sample:user:1")).isEqualTo("sample:lock:cache:user:1");
        assertThat(properties.buildKey("bloom:", "bloom:blacklist")).isEqualTo("sample:bloom:blacklist");
        assertThat(properties.buildKey("idemp", ":pay:99")).isEqualTo("sample:idemp:pay:99");
    }
}
