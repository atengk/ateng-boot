package io.github.atengk.boot.redis.cache.twolevel.l1;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ConcurrentLruMemoryCache 单元测试
 * 验证 LRU 淘汰算法、容量硬边界防 OOM、TTL 过期及并发线程安全性
 *
 * @author Ateng
 * @since 2026-10-19
 */
@DisplayName("ConcurrentLruMemoryCache 降级 LRU 缓存单元测试")
class ConcurrentLruMemoryCacheTest {

    @Test
    @DisplayName("基础 CRUD：正常读写、淘汰与清空")
    void testBasicOperations() {
        ConcurrentLruMemoryCache cache = new ConcurrentLruMemoryCache(10, Duration.ofMinutes(5));

        cache.put("k1", "v1");
        cache.put("k2", "v2");

        assertThat(cache.get("k1")).isEqualTo("v1");
        assertThat(cache.get("k2")).isEqualTo("v2");
        assertThat(cache.get("k3")).isNull();
        assertThat(cache.size()).isEqualTo(2);

        cache.evict("k1");
        assertThat(cache.get("k1")).isNull();
        assertThat(cache.size()).isEqualTo(1);

        cache.clear();
        assertThat(cache.size()).isEqualTo(0);
        assertThat(cache.get("k2")).isNull();
    }

    @Test
    @DisplayName("LRU 容量严格防线：超出 maxSize 自动淘汰最久未访问条目")
    void testLruEviction() {
        // 最大容量限制为 3
        ConcurrentLruMemoryCache cache = new ConcurrentLruMemoryCache(3, Duration.ofMinutes(5));

        cache.put("k1", "v1");
        cache.put("k2", "v2");
        cache.put("k3", "v3");

        // 访问 k1，使 k1 成为最近被访问的条目（k2 成为最久未访问条目）
        assertThat(cache.get("k1")).isEqualTo("v1");

        // 插入第 4 个条目，触发 LRU 淘汰，最久未使用的 k2 应被淘汰
        cache.put("k4", "v4");

        assertThat(cache.size()).isEqualTo(3);
        assertThat(cache.get("k2")).isNull();
        assertThat(cache.get("k1")).isEqualTo("v1");
        assertThat(cache.get("k3")).isEqualTo("v3");
        assertThat(cache.get("k4")).isEqualTo("v4");
    }

    @Test
    @DisplayName("TTL 过期防线：过期条目读取时返回 null 并自动移除")
    void testTtlExpiration() throws InterruptedException {
        // 设置极短 TTL 50ms
        ConcurrentLruMemoryCache cache = new ConcurrentLruMemoryCache(10, Duration.ofMillis(50));

        cache.put("tempKey", "tempValue");
        assertThat(cache.get("tempKey")).isEqualTo("tempValue");

        // 等待超时
        Thread.sleep(70);

        assertThat(cache.get("tempKey")).isNull();
        assertThat(cache.size()).isEqualTo(0);
    }

    @Test
    @DisplayName("高并发读写安全性：多线程并发写入与读取无异常与数据脏写")
    void testConcurrency() throws InterruptedException {
        int threadCount = 10;
        int operationsPerThread = 500;
        ConcurrentLruMemoryCache cache = new ConcurrentLruMemoryCache(100, Duration.ofMinutes(1));

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        try {
            CountDownLatch latch = new CountDownLatch(threadCount);
            AtomicInteger errorCount = new AtomicInteger(0);

            for (int i = 0; i < threadCount; i++) {
                final int threadId = i;
                executor.submit(() -> {
                    try {
                        for (int j = 0; j < operationsPerThread; j++) {
                            String key = "key-" + (j % 50);
                            cache.put(key, "val-" + threadId + "-" + j);
                            cache.get(key);
                            if (j % 10 == 0) {
                                cache.evict(key);
                            }
                        }
                    } catch (Exception e) {
                        errorCount.incrementAndGet();
                    } finally {
                        latch.countDown();
                    }
                });
            }

            boolean completed = latch.await(10, TimeUnit.SECONDS);
            assertThat(completed).isTrue();
            assertThat(errorCount.get()).isEqualTo(0);
            assertThat(cache.size()).isLessThanOrEqualTo(100);
        } finally {
            executor.shutdownNow();
        }
    }
}
