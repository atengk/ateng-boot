package io.github.atengk.boot.core.concurrent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 纯原生有界 LRU 缓存单元测试
 *
 * @author Ateng
 * @since 2026-10-27
 */
class ConcurrentLruCacheTest {

    @Test
    @DisplayName("基础增删查改与状态判断测试")
    void testBasicOperations() {
        ConcurrentLruCache<String, String> cache = new ConcurrentLruCache<>(3);
        assertTrue(cache.isEmpty());
        assertEquals(0, cache.size());
        assertEquals(3, cache.getMaxCapacity());
        assertNull(cache.getTtl());

        assertNull(cache.put("k1", "v1"));
        assertEquals("v1", cache.get("k1"));
        assertEquals(1, cache.size());
        assertFalse(cache.isEmpty());
        assertTrue(cache.containsKey("k1"));

        assertEquals("v1", cache.put("k1", "v1_updated"));
        assertEquals("v1_updated", cache.get("k1"));

        assertEquals("v1_updated", cache.remove("k1"));
        assertNull(cache.get("k1"));
        assertFalse(cache.containsKey("k1"));
        assertEquals(0, cache.size());

        cache.put("k2", "v2");
        cache.put("k3", "v3");
        assertEquals(2, cache.size());
        cache.clear();
        assertEquals(0, cache.size());
        assertTrue(cache.isEmpty());
    }

    @Test
    @DisplayName("LRU 容量淘汰机制验证 (最久未访问优先淘汰)")
    void testLruEviction() {
        ConcurrentLruCache<String, Integer> cache = new ConcurrentLruCache<>(3);
        cache.put("A", 1);
        cache.put("B", 2);
        cache.put("C", 3);

        // 访问 A，使得顺序变为: B, C, A
        assertEquals(1, cache.get("A"));

        // 写入 D，此时容量超限，最久未访问的 B 应被自动淘汰
        cache.put("D", 4);

        assertEquals(3, cache.size());
        assertNull(cache.get("B"), "B 应该已被 LRU 机制淘汰");
        assertEquals(1, cache.get("A"));
        assertEquals(3, cache.get("C"));
        assertEquals(4, cache.get("D"));
    }

    @Test
    @DisplayName("computeIfAbsent 原子计算与防击穿测试")
    void testComputeIfAbsent() {
        ConcurrentLruCache<String, String> cache = new ConcurrentLruCache<>(5);
        AtomicInteger computeCount = new AtomicInteger(0);

        String v1 = cache.computeIfAbsent("user:1", k -> {
            computeCount.incrementAndGet();
            return "Alice";
        });
        assertEquals("Alice", v1);
        assertEquals(1, computeCount.get());

        // 再次获取相同 key，不应再次触发计算
        String v2 = cache.computeIfAbsent("user:1", k -> {
            computeCount.incrementAndGet();
            return "Bob";
        });
        assertEquals("Alice", v2);
        assertEquals(1, computeCount.get(), "已存在的 key 不应重复计算");

        // 计算结果为 null 时不存入
        String vNull = cache.computeIfAbsent("user:null", k -> null);
        assertNull(vNull);
        assertFalse(cache.containsKey("user:null"));
    }

    @Test
    @DisplayName("TTL 存活时间惰性过期测试")
    void testTtlExpiration() throws InterruptedException {
        ConcurrentLruCache<String, String> cache = new ConcurrentLruCache<>(5, Duration.ofMillis(60));
        assertNotNull(cache.getTtl());
        assertEquals(Duration.ofMillis(60), cache.getTtl());

        cache.put("token:1", "valid_token");
        assertEquals("valid_token", cache.get("token:1"));
        assertTrue(cache.containsKey("token:1"));

        // 等待超过 TTL 时间
        Thread.sleep(80);

        assertNull(cache.get("token:1"), "超过 TTL 后读取应返回 null 并被惰性移除");
        assertFalse(cache.containsKey("token:1"), "超过 TTL 后 containsKey 应为 false");
    }

    @Test
    @DisplayName("cleanUp 主动扫描清理过期条目测试")
    void testCleanUp() throws InterruptedException {
        ConcurrentLruCache<String, String> cache = new ConcurrentLruCache<>(10, Duration.ofMillis(50));
        cache.put("k1", "v1");
        cache.put("k2", "v2");
        cache.put("k3", "v3");

        Thread.sleep(70);

        int cleaned = cache.cleanUp();
        assertEquals(3, cleaned);
        assertEquals(0, cache.size());
    }

    @Test
    @DisplayName("边界值与非空防御测试")
    void testBoundaryDefenses() {
        assertThrows(IllegalArgumentException.class, () -> new ConcurrentLruCache<>(0));
        assertThrows(IllegalArgumentException.class, () -> new ConcurrentLruCache<>(-1));
        assertThrows(IllegalArgumentException.class, () -> new ConcurrentLruCache<>(5, Duration.ofMillis(-10)));

        ConcurrentLruCache<String, String> cache = new ConcurrentLruCache<>(5);
        assertThrows(NullPointerException.class, () -> cache.put(null, "v"));
        assertThrows(NullPointerException.class, () -> cache.put("k", null));
        assertThrows(NullPointerException.class, () -> cache.get(null));
        assertThrows(NullPointerException.class, () -> cache.computeIfAbsent(null, k -> "v"));
        assertThrows(NullPointerException.class, () -> cache.computeIfAbsent("k", null));
    }

    @Test
    @DisplayName("高并发多线程竞争与容量防溢出测试")
    void testConcurrentAccess() throws InterruptedException {
        int capacity = 50;
        ConcurrentLruCache<Integer, String> cache = new ConcurrentLruCache<>(capacity);
        int threadCount = 16;
        int operationsPerThread = 200;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);

        for (int t = 0; t < threadCount; t++) {
            final int threadId = t;
            executor.submit(() -> {
                try {
                    for (int i = 0; i < operationsPerThread; i++) {
                        int key = (threadId * 10) + (i % 20);
                        cache.computeIfAbsent(key, k -> "val_" + k);
                        cache.get(key);
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        executor.shutdown();

        assertTrue(cache.size() <= capacity, "并发操作下缓存总条目绝对不得超过最大容量");
    }
}
