package io.github.atengk.boot.core.id;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Enumeration;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.LongSupplier;

/**
 * 纯 JDK 17 原生 Twitter Snowflake 雪花算法发号引擎
 * 结构规范：1-bit 符号位 (0) + 41-bit 时间戳 + 5-bit 数据中心 ID (0~31) + 5-bit 工作节点 ID (0~31) + 12-bit 序列号 (0~4095)
 * 具备轻量 NTP 时钟回拨自旋容忍（<= 5ms 自旋等待追平，> 5ms 抛出 IllegalStateException 熔断）
 * 支持四级自适应节点推导（系统属性/环境变量 -> 网卡 MAC 哈希 -> IP 哈希 -> 安全随机数降级兜底）
 *
 * @author Ateng
 * @since 2026-10-16
 */
public class Snowflake {

    private static final Logger log = LoggerFactory.getLogger(Snowflake.class);

    /**
     * 框架默认基准时间戳（2026-01-01 00:00:00 UTC）
     * 41 位时间戳可支撑发号至 2095 年
     */
    public static final long DEFAULT_EPOCH = 1767225600000L;

    private static final long WORKER_ID_BITS = 5L;
    private static final long DATACENTER_ID_BITS = 5L;
    private static final long MAX_WORKER_ID = -1L ^ (-1L << WORKER_ID_BITS); // 31
    private static final long MAX_DATACENTER_ID = -1L ^ (-1L << DATACENTER_ID_BITS); // 31
    private static final long SEQUENCE_BITS = 12L;

    private static final long WORKER_ID_SHIFT = SEQUENCE_BITS; // 12
    private static final long DATACENTER_ID_SHIFT = SEQUENCE_BITS + WORKER_ID_BITS; // 17
    private static final long TIMESTAMP_LEFT_SHIFT = SEQUENCE_BITS + WORKER_ID_BITS + DATACENTER_ID_BITS; // 22
    private static final long SEQUENCE_MASK = -1L ^ (-1L << SEQUENCE_BITS); // 4095

    private static final long MAX_BACKWARD_MS = 5L;

    private final long workerId;
    private final long datacenterId;
    private final long epoch;
    private final LongSupplier timeSupplier;

    private long sequence = 0L;
    private long lastTimestamp = -1L;

    /**
     * 默认构造函数：自适应推导 workerId 与 datacenterId，采用框架默认基准时间戳
     */
    public Snowflake() {
        this(deriveWorkerId(), deriveDatacenterId(), DEFAULT_EPOCH);
    }

    /**
     * 指定节点 ID 构造函数，采用框架默认基准时间戳
     *
     * @param workerId     工作节点 ID (0~31)
     * @param datacenterId 数据中心 ID (0~31)
     */
    public Snowflake(long workerId, long datacenterId) {
        this(workerId, datacenterId, DEFAULT_EPOCH);
    }

    /**
     * 全参构造函数
     *
     * @param workerId     工作节点 ID (0~31)
     * @param datacenterId 数据中心 ID (0~31)
     * @param epoch        基准时间戳（毫秒）
     */
    public Snowflake(long workerId, long datacenterId, long epoch) {
        this(workerId, datacenterId, epoch, System::currentTimeMillis);
    }

    /**
     * 包级测试构造函数（支持时间生成器依赖注入）
     *
     * @param workerId     工作节点 ID (0~31)
     * @param datacenterId 数据中心 ID (0~31)
     * @param epoch        基准时间戳（毫秒）
     * @param timeSupplier 毫秒时间生成器
     */
    Snowflake(long workerId, long datacenterId, long epoch, LongSupplier timeSupplier) {
        if (workerId > MAX_WORKER_ID || workerId < 0) {
            throw new IllegalArgumentException(String.format("Worker ID can't be greater than %d or less than 0, current: %d", MAX_WORKER_ID, workerId));
        }
        if (datacenterId > MAX_DATACENTER_ID || datacenterId < 0) {
            throw new IllegalArgumentException(String.format("Datacenter ID can't be greater than %d or less than 0, current: %d", MAX_DATACENTER_ID, datacenterId));
        }
        if (epoch > timeSupplier.getAsLong()) {
            throw new IllegalArgumentException(String.format("Epoch can't be greater than current timestamp, epoch: %d", epoch));
        }
        this.workerId = workerId;
        this.datacenterId = datacenterId;
        this.epoch = epoch;
        this.timeSupplier = timeSupplier;
    }

    /**
     * 生成下一个全局唯一长整型雪花 ID（线程安全）
     *
     * @return 64 位全局唯一递增雪花 ID
     */
    public synchronized long nextId() {
        long timestamp = timeGen();

        // 1. 时钟回拨防御
        if (timestamp < lastTimestamp) {
            long offset = lastTimestamp - timestamp;
            if (offset <= MAX_BACKWARD_MS) {
                try {
                    Thread.sleep(offset);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                timestamp = timeGen();
                if (timestamp < lastTimestamp) {
                    throw new IllegalStateException(String.format("Clock moved backwards. Refusing to generate id for %d milliseconds", lastTimestamp - timestamp));
                }
            } else {
                throw new IllegalStateException(String.format("Clock moved backwards. Refusing to generate id for %d milliseconds", offset));
            }
        }

        // 2. 毫秒内序列递增与溢出处理
        if (lastTimestamp == timestamp) {
            sequence = (sequence + 1) & SEQUENCE_MASK;
            if (sequence == 0) {
                timestamp = tilNextMillis(lastTimestamp);
            }
        } else {
            sequence = 0L;
        }

        lastTimestamp = timestamp;

        // 3. 位运算拼装 64 位 ID
        return ((timestamp - epoch) << TIMESTAMP_LEFT_SHIFT)
                | (datacenterId << DATACENTER_ID_SHIFT)
                | (workerId << WORKER_ID_SHIFT)
                | sequence;
    }

    /**
     * 生成下一个雪花 ID 的字符串形式
     *
     * @return 字符串形式的全局唯一雪花 ID
     */
    public String nextIdStr() {
        return Long.toString(nextId());
    }

    /**
     * 自旋等待直至进入下一毫秒
     *
     * @param lastTimestamp 上一次发号毫秒时间戳
     * @return 下一毫秒时间戳
     */
    private long tilNextMillis(long lastTimestamp) {
        long timestamp = timeGen();
        while (timestamp <= lastTimestamp) {
            timestamp = timeGen();
        }
        return timestamp;
    }

    /**
     * 获取当前系统毫秒时间
     *
     * @return 当前毫秒时间戳
     */
    private long timeGen() {
        return timeSupplier.getAsLong();
    }

    /**
     * 获取当前工作节点 ID
     *
     * @return 工作节点 ID (0~31)
     */
    public long getWorkerId() {
        return workerId;
    }

    /**
     * 获取当前数据中心 ID
     *
     * @return 数据中心 ID (0~31)
     */
    public long getDatacenterId() {
        return datacenterId;
    }

    /**
     * 获取当前基准时间戳
     *
     * @return 基准时间戳（毫秒）
     */
    public long getEpoch() {
        return epoch;
    }

    // ==================== 静态工具与位段解析方法 ====================

    /**
     * 从生成的雪花 ID 中反向解析发号时间戳（毫秒）
     *
     * @param id    雪花 ID
     * @param epoch 生成该 ID 所使用的基准时间戳（毫秒）
     * @return 发号毫秒时间戳
     */
    public static long getTimestamp(long id, long epoch) {
        return (id >> TIMESTAMP_LEFT_SHIFT) + epoch;
    }

    /**
     * 从生成的雪花 ID 中反向解析工作节点 ID
     *
     * @param id 雪花 ID
     * @return 工作节点 ID (0~31)
     */
    public static long getWorkerId(long id) {
        return (id >> WORKER_ID_SHIFT) & MAX_WORKER_ID;
    }

    /**
     * 从生成的雪花 ID 中反向解析数据中心 ID
     *
     * @param id 雪花 ID
     * @return 数据中心 ID (0~31)
     */
    public static long getDatacenterId(long id) {
        return (id >> DATACENTER_ID_SHIFT) & MAX_DATACENTER_ID;
    }

    /**
     * 从生成的雪花 ID 中反向解析毫秒内序列号
     *
     * @param id 雪花 ID
     * @return 序列号 (0~4095)
     */
    public static long getSequence(long id) {
        return id & SEQUENCE_MASK;
    }

    // ==================== 自适应四级节点推导实现 ====================

    /**
     * 四级平滑推导 workerId (0~31)
     * 1. JVM 系统属性/环境变量 -> 2. 网卡 MAC 地址哈希 -> 3. 本机 IP 地址低位哈希 -> 4. 随机数兜底
     *
     * @return 推导得到的 workerId
     */
    private static long deriveWorkerId() {
        // 1. 系统属性与环境变量
        String prop = System.getProperty("ateng.boot.snowflake.worker-id");
        if (prop == null || prop.isBlank()) {
            prop = System.getenv("ATENG_BOOT_SNOWFLAKE_WORKER_ID");
        }
        if (prop != null && !prop.isBlank()) {
            try {
                long id = Long.parseLong(prop.trim());
                if (id >= 0 && id <= MAX_WORKER_ID) {
                    return id;
                }
            } catch (NumberFormatException ignored) {
            }
        }
        // 2. 网卡 MAC 地址哈希
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces != null && interfaces.hasMoreElements()) {
                NetworkInterface ni = interfaces.nextElement();
                if (!ni.isLoopback() && ni.isUp()) {
                    byte[] mac = ni.getHardwareAddress();
                    if (mac != null && mac.length > 0) {
                        int sum = 0;
                        for (byte b : mac) {
                            sum = (sum << 5) - sum + (b & 0xFF);
                        }
                        return Math.abs(sum) % (MAX_WORKER_ID + 1);
                    }
                }
            }
        } catch (Exception ignored) {
        }
        // 3. 本机 IP 地址低位哈希
        try {
            InetAddress localHost = InetAddress.getLocalHost();
            byte[] address = localHost.getAddress();
            if (address != null && address.length > 0) {
                return (address[address.length - 1] & 0xFF) % (MAX_WORKER_ID + 1);
            }
        } catch (Exception ignored) {
        }
        // 4. 随机兜底
        long fallback = ThreadLocalRandom.current().nextLong(MAX_WORKER_ID + 1);
        log.warn("无法从环境变量、网卡 MAC 或本机 IP 推导 workerId，降级采用随机兜底值: {}", fallback);
        return fallback;
    }

    /**
     * 四级平滑推导 datacenterId (0~31)
     * 1. JVM 系统属性/环境变量 -> 2. 网卡 MAC 地址异或哈希 -> 3. 本机 IP 地址次低位哈希 -> 4. 随机数兜底
     *
     * @return 推导得到的 datacenterId
     */
    private static long deriveDatacenterId() {
        // 1. 系统属性与环境变量
        String prop = System.getProperty("ateng.boot.snowflake.datacenter-id");
        if (prop == null || prop.isBlank()) {
            prop = System.getenv("ATENG_BOOT_SNOWFLAKE_DATACENTER_ID");
        }
        if (prop != null && !prop.isBlank()) {
            try {
                long id = Long.parseLong(prop.trim());
                if (id >= 0 && id <= MAX_DATACENTER_ID) {
                    return id;
                }
            } catch (NumberFormatException ignored) {
            }
        }
        // 2. 网卡 MAC 地址异或哈希
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces != null && interfaces.hasMoreElements()) {
                NetworkInterface ni = interfaces.nextElement();
                if (!ni.isLoopback() && ni.isUp()) {
                    byte[] mac = ni.getHardwareAddress();
                    if (mac != null && mac.length >= 2) {
                        return ((mac[0] & 0xFF) ^ (mac[1] & 0xFF)) % (MAX_DATACENTER_ID + 1);
                    }
                }
            }
        } catch (Exception ignored) {
        }
        // 3. 本机 IP 地址次低位哈希
        try {
            InetAddress localHost = InetAddress.getLocalHost();
            byte[] address = localHost.getAddress();
            if (address != null && address.length >= 2) {
                return (address[address.length - 2] & 0xFF) % (MAX_DATACENTER_ID + 1);
            }
        } catch (Exception ignored) {
        }
        // 4. 随机兜底
        long fallback = ThreadLocalRandom.current().nextLong(MAX_DATACENTER_ID + 1);
        log.warn("无法从环境变量、网卡 MAC 或本机 IP 推导 datacenterId，降级采用随机兜底值: {}", fallback);
        return fallback;
    }
}
