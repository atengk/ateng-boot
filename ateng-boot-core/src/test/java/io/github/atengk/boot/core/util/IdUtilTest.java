package io.github.atengk.boot.core.util;

import io.github.atengk.boot.core.id.Snowflake;
import io.github.atengk.boot.core.id.SnowflakeIdInfo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 分布式唯一 ID 门面工具类单测与并发验证
 *
 * @author Ateng
 * @since 2026-10-16
 */
@DisplayName("分布式唯一 ID 门面工具类测试")
class IdUtilTest {

    @Test
    @DisplayName("雪花算法门面：单例发号、字符串形式与工厂创建验证")
    void shouldGenerateSnowflakeIdCorrectly() {
        long id1 = IdUtil.getSnowflakeNextId();
        long id2 = IdUtil.getSnowflakeNextId();
        assertTrue(id1 > 0);
        assertTrue(id2 > id1, "雪花 ID 必须保持单调递增");

        String idStr = IdUtil.getSnowflakeNextIdStr();
        assertNotNull(idStr);
        assertEquals(String.valueOf(Long.parseLong(idStr)), idStr);

        Snowflake defaultSnowflake = IdUtil.getSnowflake();
        assertNotNull(defaultSnowflake);

        Snowflake custom1 = IdUtil.createSnowflake(5, 10);
        assertEquals(5, custom1.getWorkerId());
        assertEquals(10, custom1.getDatacenterId());

        Snowflake custom2 = IdUtil.createSnowflake(6, 12, 1767225600000L);
        assertEquals(6, custom2.getWorkerId());
        assertEquals(12, custom2.getDatacenterId());
        assertEquals(1767225600000L, custom2.getEpoch());
    }

    @Test
    @DisplayName("高性能无横线 UUID (fastSimpleUUID)：32位、无横线且高并发无碰撞")
    void shouldGenerateFastSimpleUUIDCorrectly() throws InterruptedException {
        String uuid = IdUtil.fastSimpleUUID();
        assertNotNull(uuid);
        assertEquals(32, uuid.length());
        assertFalse(uuid.contains("-"));
        assertTrue(uuid.matches("^[0-9a-f]{32}$"), "必须为 32 位全小写 16 进制字符");

        // 并发生成 10,000 个 UUID 唯一性验证
        int count = 10_000;
        Set<String> set = ConcurrentHashMap.newKeySet(count);
        int threads = 8;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch latch = new CountDownLatch(threads);

        for (int i = 0; i < threads; i++) {
            executor.submit(() -> {
                try {
                    for (int j = 0; j < count / threads; j++) {
                        set.add(IdUtil.fastSimpleUUID());
                    }
                } finally {
                    latch.countDown();
                }
            });
        }
        latch.await();
        executor.shutdown();

        assertEquals(count, set.size(), "并发生成的 10,000 个 fastSimpleUUID 必须绝对唯一");
    }

    @Test
    @DisplayName("高性能标准 UUID (fastUUID)：36位、包含横线且符合 RFC4122 v4 格式")
    void shouldGenerateFastUUIDCorrectly() {
        String uuidStr = IdUtil.fastUUID();
        assertNotNull(uuidStr);
        assertEquals(36, uuidStr.length());
        assertTrue(uuidStr.contains("-"));

        UUID parsed = UUID.fromString(uuidStr);
        assertEquals(4, parsed.version(), "必须符合 Version 4 随机 UUID 规范");
    }

    @Test
    @DisplayName("标准 SecureRandom UUID 门面方法验证")
    void shouldGenerateStandardRandomUUID() {
        String standard = IdUtil.randomUUID();
        assertNotNull(standard);
        assertEquals(36, standard.length());

        String simple = IdUtil.simpleUUID();
        assertNotNull(simple);
        assertEquals(32, simple.length());
        assertFalse(simple.contains("-"));
    }

    @Test
    @DisplayName("轻量短 ID (nanoId)：默认 21 位、URL 安全字符集及自定义重载")
    void shouldGenerateNanoIdCorrectly() {
        String nanoId = IdUtil.nanoId();
        assertNotNull(nanoId);
        assertEquals(21, nanoId.length());
        assertTrue(nanoId.matches("^[A-Za-z0-9_-]{21}$"), "默认必须符合 21 位 URL 安全字符集");

        // 自定义长度
        String shortId = IdUtil.nanoId(10);
        assertEquals(10, shortId.length());

        // 自定义字符集（纯数字）
        char[] digits = "0123456789".toCharArray();
        String numericId = IdUtil.nanoId(8, digits);
        assertEquals(8, numericId.length());
        assertTrue(numericId.matches("^[0-9]{8}$"));

        // 异常参数防御
        assertThrows(IllegalArgumentException.class, () -> IdUtil.nanoId(0));
        assertThrows(IllegalArgumentException.class, () -> IdUtil.nanoId(-5));
        assertThrows(IllegalArgumentException.class, () -> IdUtil.nanoId(10, null));
        assertThrows(IllegalArgumentException.class, () -> IdUtil.nanoId(10, new char[0]));
    }

    @Test
    @DisplayName("反射防御：私有构造函数防止外部实例化")
    void shouldPreventInstantiationViaReflection() throws NoSuchMethodException {
        Constructor<IdUtil> constructor = IdUtil.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        InvocationTargetException ex = assertThrows(InvocationTargetException.class, constructor::newInstance);
        assertTrue(ex.getCause() instanceof UnsupportedOperationException);
    }

    @Test
    @DisplayName("雪花 ID 逆向反解门面：parseSnowflake 完整性验证")
    void shouldParseSnowflakeIdViaIdUtil() {
        long id = IdUtil.nextId();
        SnowflakeIdInfo info = IdUtil.parseSnowflake(id);
        assertNotNull(info);
        assertEquals(id, info.id());
        assertTrue(info.timestamp() > 0);
        assertNotNull(info.instant());
        assertNotNull(info.dateTime());
    }

    @Test
    @DisplayName("Base62 编解码验证：双向无损转换、极值边界与异常拦截")
    void shouldEncodeAndDecodeBase62Losslessly() {
        // 1. 基础值与单字符边界
        assertEquals("0", IdUtil.toBase62(0L));
        assertEquals(0L, IdUtil.fromBase62("0"));

        assertEquals("1", IdUtil.toBase62(1L));
        assertEquals(1L, IdUtil.fromBase62("1"));

        assertEquals("z", IdUtil.toBase62(61L));
        assertEquals(61L, IdUtil.fromBase62("z"));

        assertEquals("10", IdUtil.toBase62(62L));
        assertEquals(62L, IdUtil.fromBase62("10"));

        // 2. 64 位极值边界 (Long.MAX_VALUE & Long.MIN_VALUE)
        String maxB62 = IdUtil.toBase62(Long.MAX_VALUE);
        assertNotNull(maxB62);
        assertTrue(maxB62.length() <= 11);
        assertEquals(Long.MAX_VALUE, IdUtil.fromBase62(maxB62));

        String minB62 = IdUtil.toBase62(Long.MIN_VALUE);
        assertNotNull(minB62);
        assertTrue(minB62.startsWith("-"));
        assertEquals(Long.MIN_VALUE, IdUtil.fromBase62(minB62));

        // 3. 负数双向测试
        assertEquals("-1", IdUtil.toBase62(-1L));
        assertEquals(-1L, IdUtil.fromBase62("-1"));

        assertEquals("-10", IdUtil.toBase62(-62L));
        assertEquals(-62L, IdUtil.fromBase62("-10"));

        // 4. 雪花 ID 真实高频场景压缩验证
        for (int i = 0; i < 1000; i++) {
            long snowflakeId = IdUtil.nextId();
            String compressed = IdUtil.toBase62(snowflakeId);
            assertTrue(compressed.length() >= 9 && compressed.length() <= 11, "雪花 ID 压缩后长度应为 9~11 字符");
            assertEquals(snowflakeId, IdUtil.fromBase62(compressed), "Base62 解码后必须 100% 还原原始雪花 ID");
        }

        // 5. 非法格式与溢出防御
        assertThrows(IllegalArgumentException.class, () -> IdUtil.fromBase62(null));
        assertThrows(IllegalArgumentException.class, () -> IdUtil.fromBase62(""));
        assertThrows(IllegalArgumentException.class, () -> IdUtil.fromBase62("   "));
        assertThrows(IllegalArgumentException.class, () -> IdUtil.fromBase62("-"));
        assertThrows(IllegalArgumentException.class, () -> IdUtil.fromBase62("abc#123"));
        assertThrows(IllegalArgumentException.class, () -> IdUtil.fromBase62("a b"));
        assertThrows(ArithmeticException.class, () -> IdUtil.fromBase62("999999999999999999999999999"));
    }
}
