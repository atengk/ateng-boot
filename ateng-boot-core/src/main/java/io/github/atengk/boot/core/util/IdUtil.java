package io.github.atengk.boot.core.util;

import io.github.atengk.boot.core.id.Snowflake;

import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 分布式与高性能唯一 ID 生成门面工具类
 * 基于 Zero-Bloat 原则提供高并发雪花算法发号器、高性能无锁 UUID 以及纯原生轻量短 ID (NanoId)
 *
 * @author Ateng
 * @since 2026-10-16
 */
public final class IdUtil {

    private static final Snowflake DEFAULT_SNOWFLAKE = new Snowflake();

    private static final char[] HEX_DIGITS = "0123456789abcdef".toCharArray();

    /**
     * 默认 NanoId 字符集（URL 安全字符：A-Za-z0-9_-）
     */
    public static final char[] DEFAULT_NANO_ID_ALPHABET =
            "_-0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ".toCharArray();

    /**
     * 默认 NanoId 长度（21 字符）
     */
    public static final int DEFAULT_NANO_ID_SIZE = 21;

    private IdUtil() {
        throw new UnsupportedOperationException("IdUtil is a utility class and cannot be instantiated");
    }

    // ==================== Snowflake 雪花算法发号门面 ====================

    /**
     * 获取全局默认单例雪花发号器实例（自适应推导节点 ID，默认 2026 纪元）
     *
     * @return 全局单例 Snowflake 实例
     */
    public static Snowflake getSnowflake() {
        return DEFAULT_SNOWFLAKE;
    }

    /**
     * 创建独立的雪花发号器实例（使用默认 2026 纪元）
     *
     * @param workerId     工作节点 ID (0~31)
     * @param datacenterId 数据中心 ID (0~31)
     * @return 独立的 Snowflake 实例
     */
    public static Snowflake createSnowflake(long workerId, long datacenterId) {
        return new Snowflake(workerId, datacenterId);
    }

    /**
     * 创建独立的雪花发号器实例（指定自定义纪元）
     *
     * @param workerId     工作节点 ID (0~31)
     * @param datacenterId 数据中心 ID (0~31)
     * @param epoch        基准时间戳（毫秒）
     * @return 独立的 Snowflake 实例
     */
    public static Snowflake createSnowflake(long workerId, long datacenterId, long epoch) {
        return new Snowflake(workerId, datacenterId, epoch);
    }

    /**
     * 基于默认单例雪花实例获取下一个长整型唯一 ID
     *
     * @return 64 位全局唯一递增雪花 ID
     */
    public static long getSnowflakeNextId() {
        return DEFAULT_SNOWFLAKE.nextId();
    }

    /**
     * 基于默认单例雪花实例获取下一个字符串形式的唯一 ID
     *
     * @return 字符串形式的全局唯一雪花 ID
     */
    public static String getSnowflakeNextIdStr() {
        return DEFAULT_SNOWFLAKE.nextIdStr();
    }

    // ==================== 高性能 UUID 门面 ====================

    /**
     * 获取基于 ThreadLocalRandom 的高性能 32 位无横线 UUID（去除了 '-' 符号）
     * 规避传统 SecureRandom 在高并发下的锁竞争与正则 replace 的内存拷贝开销
     *
     * @return 32 位小写 16 进制 UUID 字符串
     */
    public static String fastSimpleUUID() {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        long msb = random.nextLong();
        long lsb = random.nextLong();
        return formatUuid(msb, lsb, false);
    }

    /**
     * 获取基于 ThreadLocalRandom 的高性能 36 位标准 UUID（包含 '-' 符号）
     * 遵循 RFC 4122 Version 4 规范，避免多线程全局锁瓶颈
     *
     * @return 36 位标准格式 UUID 字符串
     */
    public static String fastUUID() {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        long msb = random.nextLong();
        long lsb = random.nextLong();
        // 设置版本号与变体标识
        msb &= ~0x000000000000F000L;
        msb |= 0x0000000000004000L; // Version 4
        lsb &= ~0xC000000000000000L;
        lsb |= 0x8000000000000000L; // IETF Variant
        return formatUuid(msb, lsb, true);
    }

    /**
     * 获取原生标准安全随机 UUID（包含 '-' 符号，基于 SecureRandom）
     *
     * @return 36 位标准格式 UUID
     */
    public static String randomUUID() {
        return UUID.randomUUID().toString();
    }

    /**
     * 获取原生标准安全随机 32 位无横线 UUID（基于 SecureRandom）
     *
     * @return 32 位无横线 UUID
     */
    public static String simpleUUID() {
        return randomUUID().replace("-", "");
    }

    /**
     * 高性能无拷贝格式化 UUID 字符串
     *
     * @param msb          高 64 位
     * @param lsb          低 64 位
     * @param isHyphenated 是否包含横线
     * @return 格式化后的字符串
     */
    private static String formatUuid(long msb, long lsb, boolean isHyphenated) {
        if (isHyphenated) {
            char[] buf = new char[36];
            formatHex(buf, 0, 8, msb >>> 32);
            buf[8] = '-';
            formatHex(buf, 9, 4, (msb >>> 16) & 0xFFFF);
            buf[13] = '-';
            formatHex(buf, 14, 4, msb & 0xFFFF);
            buf[18] = '-';
            formatHex(buf, 19, 4, lsb >>> 48);
            buf[23] = '-';
            formatHex(buf, 24, 12, lsb & 0xFFFFFFFFFFFFL);
            return new String(buf);
        } else {
            char[] buf = new char[32];
            formatHex(buf, 0, 16, msb);
            formatHex(buf, 16, 16, lsb);
            return new String(buf);
        }
    }

    private static void formatHex(char[] buf, int offset, int length, long val) {
        for (int i = offset + length - 1; i >= offset; i--) {
            buf[i] = HEX_DIGITS[(int) (val & 0x0F)];
            val >>>= 4;
        }
    }

    // ==================== 纯原生轻量短 ID (NanoId) 门面 ====================

    /**
     * 获取默认 21 位 URL 安全短 ID（NanoId）
     *
     * @return 21 位短 ID 字符串
     */
    public static String nanoId() {
        return nanoId(DEFAULT_NANO_ID_SIZE, DEFAULT_NANO_ID_ALPHABET);
    }

    /**
     * 获取指定长度的 URL 安全短 ID
     *
     * @param size 期望长度
     * @return 指定长度的短 ID 字符串
     */
    public static String nanoId(int size) {
        return nanoId(size, DEFAULT_NANO_ID_ALPHABET);
    }

    /**
     * 获取指定长度与字符集的自定义短 ID
     *
     * @param size     期望长度（必须大于 0）
     * @param alphabet 字符集候选数组（不能为空）
     * @return 自定义短 ID 字符串
     */
    public static String nanoId(int size, char[] alphabet) {
        if (size <= 0) {
            throw new IllegalArgumentException("Size must be greater than 0, current: " + size);
        }
        if (alphabet == null || alphabet.length == 0) {
            throw new IllegalArgumentException("Alphabet must not be null or empty");
        }
        ThreadLocalRandom random = ThreadLocalRandom.current();
        char[] chars = new char[size];
        int alphabetLen = alphabet.length;
        for (int i = 0; i < size; i++) {
            chars[i] = alphabet[random.nextInt(alphabetLen)];
        }
        return new String(chars);
    }
}
