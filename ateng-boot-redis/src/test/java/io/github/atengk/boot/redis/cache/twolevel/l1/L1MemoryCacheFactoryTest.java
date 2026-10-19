package io.github.atengk.boot.redis.cache.twolevel.l1;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * L1MemoryCacheFactory 单元测试
 *
 * @author Ateng
 * @since 2026-10-19
 */
@DisplayName("L1MemoryCacheFactory 探测工厂单元测试")
class L1MemoryCacheFactoryTest {

    @Test
    @DisplayName("探测类路径中的 Caffeine 并成功装配 CaffeineL1MemoryCache")
    void testCreateCacheWithCaffeine() {
        assertThat(L1MemoryCacheFactory.isCaffeinePresent()).isTrue();

        L1MemoryCache cache = L1MemoryCacheFactory.create(100, Duration.ofMinutes(5));
        assertThat(cache).isInstanceOf(CaffeineL1MemoryCache.class);

        cache.put("user:1", "Alice");
        assertThat(cache.get("user:1")).isEqualTo("Alice");
        assertThat(cache.size()).isEqualTo(1);

        cache.evict("user:1");
        assertThat(cache.get("user:1")).isNull();

        cache.put("user:2", "Bob");
        cache.clear();
        assertThat(cache.get("user:2")).isNull();
    }
}
