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

    @Test
    @DisplayName("显式降级模式：当未启用 Caffeine 时平滑装配 ConcurrentLruMemoryCache")
    void testFallbackToConcurrentLruMemoryCache() {
        L1MemoryCache cache = L1MemoryCacheFactory.create(100, Duration.ofMinutes(5), false);
        assertThat(cache).isInstanceOf(ConcurrentLruMemoryCache.class);

        cache.put("user:3", "Charlie");
        assertThat(cache.get("user:3")).isEqualTo("Charlie");
        assertThat(cache.size()).isEqualTo(1);

        cache.evict("user:3");
        assertThat(cache.get("user:3")).isNull();

        cache.put("user:4", "David");
        cache.clear();
        assertThat(cache.get("user:4")).isNull();
    }

    @Test
    @DisplayName("类加载器隔离全真仿真：在屏蔽 Caffeine 类路径时自动退化为 ConcurrentLruMemoryCache")
    void testIsolatedClassLoaderFallback() throws Exception {
        ClassLoader isolatedClassLoader = new ClassLoader(getClass().getClassLoader()) {
            @Override
            public Class<?> loadClass(String name) throws ClassNotFoundException {
                if (name.startsWith("com.github.benmanes.caffeine")) {
                    throw new ClassNotFoundException("Simulated missing Caffeine dependency");
                }
                return super.loadClass(name);
            }
        };

        boolean present = L1MemoryCacheFactory.isCaffeinePresent(isolatedClassLoader);
        assertThat(present).isFalse();

        L1MemoryCache cache = L1MemoryCacheFactory.create(50L, Duration.ofMinutes(2), present);
        assertThat(cache).isInstanceOf(ConcurrentLruMemoryCache.class);
    }
}
