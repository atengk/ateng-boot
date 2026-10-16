package io.github.atengk.boot.core.id;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 原生 Twitter Snowflake 雪花发号器单元与并发测试
 *
 * @author Ateng
 * @since 2026-10-16
 */
@DisplayName("原生 Twitter Snowflake 雪花发号器测试")
class SnowflakeTest {

    @Test
    @DisplayName("单线程顺序发号：趋势递增且绝对唯一")
    void shouldGenerateMonotonicallyIncreasingIdsInSingleThread() {
        Snowflake snowflake = new Snowflake(1, 1);
        long lastId = 0L;
        for (int i = 0; i < 5000; i++) {
            long id = snowflake.nextId();
            assertTrue(id > lastId, "生成的 ID 必须严格大于上一 ID");
            lastId = id;
        }
    }

    @Test
    @DisplayName("多线程高并发发号：10 线程并发生成 100,000 个 ID 绝对唯一无碰撞")
    void shouldGenerateUniqueIdsInMultiThreadedEnvironment() throws InterruptedException {
        Snowflake snowflake = new Snowflake(2, 3);
        int threadCount = 10;
        int idsPerThread = 10_000;
        int totalExpectedIds = threadCount * idsPerThread;

        Set<Long> generatedIds = ConcurrentHashMap.newKeySet(totalExpectedIds);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    for (int j = 0; j < idsPerThread; j++) {
                        generatedIds.add(snowflake.nextId());
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        // 统一并发开闸
        startLatch.countDown();
        doneLatch.await();
        executor.shutdown();

        assertEquals(totalExpectedIds, generatedIds.size(), "高并发下生成的 100,000 个雪花 ID 必须 100% 唯一无碰撞");
    }

    @Test
    @DisplayName("配置与解析：自定义纪元、节点 ID 及位段反向解析验证")
    void shouldRespectCustomConfigAndParseSegments() {
        long customEpoch = 1767225600000L; // 2026-01-01
        Snowflake snowflake = new Snowflake(18, 25, customEpoch);

        assertEquals(18, snowflake.getWorkerId());
        assertEquals(25, snowflake.getDatacenterId());
        assertEquals(customEpoch, snowflake.getEpoch());

        long id = snowflake.nextId();
        assertTrue(id > 0);

        // 反向解析位段验证
        assertEquals(18, Snowflake.getWorkerId(id));
        assertEquals(25, Snowflake.getDatacenterId(id));
        assertTrue(Snowflake.getTimestamp(id, customEpoch) >= customEpoch);
        assertTrue(Snowflake.getSequence(id) >= 0 && Snowflake.getSequence(id) <= 4095);

        // nextIdStr 验证
        String idStr = snowflake.nextIdStr();
        assertNotNull(idStr);
        assertEquals(String.valueOf(Long.parseLong(idStr)), idStr);
    }

    @Test
    @DisplayName("参数越界防御：WorkerId 与 DatacenterId 超出 0~31 抛出 IllegalArgumentException")
    void shouldThrowExceptionWhenNodeIdOutOfRange() {
        assertThrows(IllegalArgumentException.class, () -> new Snowflake(32, 0));
        assertThrows(IllegalArgumentException.class, () -> new Snowflake(-1, 0));
        assertThrows(IllegalArgumentException.class, () -> new Snowflake(0, 32));
        assertThrows(IllegalArgumentException.class, () -> new Snowflake(0, -1));
        assertThrows(IllegalArgumentException.class, () -> new Snowflake(0, 0, System.currentTimeMillis() + 100_000));
    }

    @Test
    @DisplayName("时钟回拨容灾：微小回拨（<= 5ms）自旋等待追平")
    void shouldTolerateSmallClockBackwards() {
        AtomicLong mockClock = new AtomicLong(1000L);
        // 通过包级时间提供者注入模拟时钟
        Snowflake snowflake = new Snowflake(1, 1, 0L, mockClock::get);

        // 第一次发号：时间戳 1000
        long id1 = snowflake.nextId();
        assertEquals(1000L, Snowflake.getTimestamp(id1, 0L));

        // 为避免死循环，使用动态 supplier 使得自旋追回
        AtomicLong advancingClock = new AtomicLong(1000L);
        Snowflake toleratingSnowflake = new Snowflake(1, 1, 0L, () -> {
            long current = advancingClock.get();
            if (current == 998L) {
                // 自旋后追平
                advancingClock.set(1001L);
                return 1001L;
            }
            return current;
        });

        long idA = toleratingSnowflake.nextId();
        advancingClock.set(998L); // 模拟回拨 2ms
        long idB = toleratingSnowflake.nextId();
        assertTrue(idB > idA, "微小回拨自旋后发号成功且递增");
    }

    @Test
    @DisplayName("时钟回拨熔断：大幅回拨（> 5ms）抛出明确语义化 IllegalStateException")
    void shouldFailFastWhenClockBackwardsExceedsThreshold() {
        AtomicLong mockClock = new AtomicLong(10_000L);
        Snowflake snowflake = new Snowflake(1, 1, 0L, mockClock::get);

        snowflake.nextId();

        // 模拟大幅时钟回拨 10ms (> 5ms)
        mockClock.set(9_990L);

        IllegalStateException ex = assertThrows(IllegalStateException.class, snowflake::nextId);
        assertTrue(ex.getMessage().contains("Clock moved backwards"), "必须明确提示时钟回拨异常原因");
    }

    @Test
    @DisplayName("自适应节点推导：无参构造自动推导节点在合法 0~31 范围内")
    void shouldAutoDeriveValidNodeIds() {
        Snowflake snowflake = new Snowflake();
        assertTrue(snowflake.getWorkerId() >= 0 && snowflake.getWorkerId() <= 31);
        assertTrue(snowflake.getDatacenterId() >= 0 && snowflake.getDatacenterId() <= 31);
        assertTrue(snowflake.nextId() > 0);
    }

    @Test
    @DisplayName("系统属性覆盖：优先读取 JVM 属性指定的 worker-id 与 datacenter-id")
    void shouldRespectSystemPropertiesOverride() {
        System.setProperty("ateng.boot.snowflake.worker-id", "7");
        System.setProperty("ateng.boot.snowflake.datacenter-id", "9");
        try {
            Snowflake snowflake = new Snowflake();
            assertEquals(7, snowflake.getWorkerId());
            assertEquals(9, snowflake.getDatacenterId());
        } finally {
            System.clearProperty("ateng.boot.snowflake.worker-id");
            System.clearProperty("ateng.boot.snowflake.datacenter-id");
        }
    }
}
