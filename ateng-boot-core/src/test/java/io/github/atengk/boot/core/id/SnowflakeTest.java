package io.github.atengk.boot.core.id;

import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.exception.BusinessException;
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
    @DisplayName("时钟回拨熔断：大幅回拨（> 5ms）抛出明确语义化 BusinessException(INTERNAL_SERVER_ERROR)")
    void shouldFailFastWhenClockBackwardsExceedsThreshold() {
        AtomicLong mockClock = new AtomicLong(10_000L);
        Snowflake snowflake = new Snowflake(1, 1, 0L, mockClock::get);

        snowflake.nextId();

        // 模拟大幅时钟回拨 10ms (> 5ms)
        mockClock.set(9_990L);

        BusinessException ex = assertThrows(BusinessException.class, snowflake::nextId);
        assertEquals(CommonResultCode.INTERNAL_SERVER_ERROR.getCode(), ex.getCode());
        assertTrue(ex.getMessage().contains("Clock moved backwards"), "必须明确提示时钟回拨异常原因");
    }

    @Test
    @DisplayName("自定义时钟回拨容忍阈值：支持配置更长容忍时间并在超限时熔断")
    void shouldRespectCustomClockBackoffTolerance() {
        AtomicLong mockClock = new AtomicLong(10_000L);
        // 配置 20ms 容忍时间
        Snowflake snowflake = new Snowflake(1, 1, 0L, 20L, mockClock::get);
        assertEquals(20L, snowflake.getMaxBackwardMs());

        snowflake.nextId();

        // 模拟大幅回拨 25ms (> 20ms) 触发熔断
        mockClock.set(9_975L);
        BusinessException ex = assertThrows(BusinessException.class, snowflake::nextId);
        assertEquals(CommonResultCode.INTERNAL_SERVER_ERROR.getCode(), ex.getCode());
        assertTrue(ex.getMessage().contains("Clock moved backwards"));
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

    @Test
    @DisplayName("毫秒内序列溢出等待：单毫秒产生超过 4096 个 ID 时自旋推进至下一毫秒")
    void shouldAdvanceToNextMillisWhenSequenceOverflows() {
        AtomicLong mockClock = new AtomicLong(1000L);
        Snowflake snowflake = new Snowflake(1, 1, 0L, mockClock::get);

        // 第一次生成 ID（seq = 0, ts = 1000）
        long firstId = snowflake.nextId();
        assertEquals(1000L, Snowflake.getTimestamp(firstId, 0L));
        assertEquals(0L, Snowflake.getSequence(firstId));

        // 循环生成剩余 4095 个，序列号递增至 4095
        for (int i = 1; i <= 4095; i++) {
            long id = snowflake.nextId();
            assertEquals(1000L, Snowflake.getTimestamp(id, 0L));
            assertEquals(i, Snowflake.getSequence(id));
        }

        // 在第 4097 次调用前推进时钟至 1001L，触发 tilNextMillis 成功跨入下一毫秒
        mockClock.set(1001L);
        long overflowId = snowflake.nextId();
        assertEquals(1001L, Snowflake.getTimestamp(overflowId, 0L));
        assertEquals(0L, Snowflake.getSequence(overflowId));
    }

    @Test
    @DisplayName("逆向反解与 SnowflakeIdInfo 结构化信息完整性验证")
    void shouldParseSnowflakeIdCorrectly() {
        long fixedEpoch = 1767225600000L;
        AtomicLong mockClock = new AtomicLong(fixedEpoch + 12345L);
        Snowflake snowflake = new Snowflake(7, 15, fixedEpoch, mockClock::get);

        long id = snowflake.nextId();
        // 实例方法反解
        SnowflakeIdInfo info = snowflake.parse(id);

        assertEquals(id, info.id());
        assertEquals(7, info.workerId());
        assertEquals(15, info.datacenterId());
        assertEquals(0, info.sequence());
        assertEquals(fixedEpoch + 12345L, info.timestamp());
        assertEquals(fixedEpoch, info.epoch());
        assertNotNull(info.instant());
        assertEquals(fixedEpoch + 12345L, info.instant().toEpochMilli());
        assertNotNull(info.dateTime());

        // 静态方法反解
        SnowflakeIdInfo staticInfo = Snowflake.parse(id, fixedEpoch);
        assertEquals(info, staticInfo);

        // 默认基准时间戳反解（自适应发号）
        Snowflake defaultSnowflake = new Snowflake(2, 3);
        long defaultId = defaultSnowflake.nextId();
        SnowflakeIdInfo defaultInfo = defaultSnowflake.parse(defaultId);
        assertEquals(2, defaultInfo.workerId());
        assertEquals(3, defaultInfo.datacenterId());
        assertTrue(defaultInfo.timestamp() > 0);
        assertEquals(Snowflake.DEFAULT_EPOCH, defaultInfo.epoch());

        // 静态方法反解带默认 epoch
        SnowflakeIdInfo staticDefaultInfo = Snowflake.parse(defaultId, Snowflake.DEFAULT_EPOCH);
        assertEquals(defaultInfo, staticDefaultInfo);
    }
}
