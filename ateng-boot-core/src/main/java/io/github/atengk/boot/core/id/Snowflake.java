package io.github.atengk.boot.core.id;

import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.exception.BusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Enumeration;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.locks.ReentrantLock;
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

    /**
     * 默认最大允许时钟回拨容忍自旋时长（5 毫秒）
     */
    public static final long DEFAULT_MAX_BACKWARD_MS = 5L;

    private final long workerId;
    private final long datacenterId;
    private final long epoch;
    private final long maxBackwardMs;
    private final LongSupplier timeSupplier;

    private final ReentrantLock lock = new ReentrantLock();
    private long sequence = 0L;
    private long lastTimestamp = -1L;

    private record NodeIds(long workerId, long datacenterId) {}

    /**
     * 默认构造函数：自适应推导 workerId 与 datacenterId，采用框架默认基准时间戳与默认时钟回拨容忍阈值（5ms）
     */
    public Snowflake() {
        this(null, null, DEFAULT_EPOCH, DEFAULT_MAX_BACKWARD_MS);
    }

    private Snowflake(NodeIds nodeIds, long epoch, long maxBackwardMs) {
        this(nodeIds.workerId(), nodeIds.datacenterId(), epoch, maxBackwardMs, System::currentTimeMillis);
    }

    /**
     * 指定节点 ID 构造函数，采用框架默认基准时间戳与默认时钟回拨容忍阈值（5ms）
     *
     * @param workerId     工作节点 ID (0~31)
     * @param datacenterId 数据中心 ID (0~31)
     */
    public Snowflake(long workerId, long datacenterId) {
        this(workerId, datacenterId, DEFAULT_EPOCH, DEFAULT_MAX_BACKWARD_MS);
    }

    /**
     * 指定节点与基准时间戳构造函数，采用默认时钟回拨容忍阈值（5ms）
     *
     * @param workerId     工作节点 ID (0~31)
     * @param datacenterId 数据中心 ID (0~31)
     * @param epoch        基准时间戳（毫秒）
     */
    public Snowflake(long workerId, long datacenterId, long epoch) {
        this(workerId, datacenterId, epoch, DEFAULT_MAX_BACKWARD_MS);
    }

    /**
     * 全参构造函数（支持自定义时钟回拨容忍阈值）
     *
     * @param workerId      工作节点 ID (0~31，为 null 时自动推导)
     * @param datacenterId  数据中心 ID (0~31，为 null 时自动推导)
     * @param epoch         基准时间戳（毫秒）
     * @param maxBackwardMs 最大允许时钟回拨容忍自旋毫秒数
     */
    public Snowflake(Long workerId, Long datacenterId, long epoch, long maxBackwardMs) {
        this(deriveNodeIds(workerId, datacenterId), epoch, maxBackwardMs);
    }

    /**
     * 包级测试构造函数（支持时间生成器依赖注入，采用默认时钟回拨容忍阈值）
     *
     * @param workerId     工作节点 ID (0~31)
     * @param datacenterId 数据中心 ID (0~31)
     * @param epoch        基准时间戳（毫秒）
     * @param timeSupplier 毫秒时间生成器
     */
    Snowflake(long workerId, long datacenterId, long epoch, LongSupplier timeSupplier) {
        this(workerId, datacenterId, epoch, DEFAULT_MAX_BACKWARD_MS, timeSupplier);
    }

    /**
     * 包级全参测试构造函数（支持时间生成器与时钟回拨容忍阈值依赖注入）
     *
     * @param workerId      工作节点 ID (0~31)
     * @param datacenterId  数据中心 ID (0~31)
     * @param epoch         基准时间戳（毫秒）
     * @param maxBackwardMs 最大允许时钟回拨容忍自旋毫秒数
     * @param timeSupplier  毫秒时间生成器
     */
    Snowflake(long workerId, long datacenterId, long epoch, long maxBackwardMs, LongSupplier timeSupplier) {
        if (workerId > MAX_WORKER_ID || workerId < 0) {
            throw new IllegalArgumentException(String.format("Worker ID can't be greater than %d or less than 0, current: %d", MAX_WORKER_ID, workerId));
        }
        if (datacenterId > MAX_DATACENTER_ID || datacenterId < 0) {
            throw new IllegalArgumentException(String.format("Datacenter ID can't be greater than %d or less than 0, current: %d", MAX_DATACENTER_ID, datacenterId));
        }
        if (epoch > timeSupplier.getAsLong()) {
            throw new IllegalArgumentException(String.format("Epoch can't be greater than current timestamp, epoch: %d", epoch));
        }
        if (maxBackwardMs < 0) {
            throw new IllegalArgumentException("maxBackwardMs 不能为负数");
        }
        this.workerId = workerId;
        this.datacenterId = datacenterId;
        this.epoch = epoch;
        this.maxBackwardMs = maxBackwardMs;
        this.timeSupplier = timeSupplier;
    }

    /**
     * 生成下一个全局唯一长整型雪花 ID（线程安全）
     * 内部使用 ReentrantLock 替代 synchronized，避免在极端时钟回拨休眠时产生虚拟线程 Carrier Thread Pinning
     *
     * @return 64 位全局唯一递增雪花 ID
     */
    public long nextId() {
        lock.lock();
        try {
            long timestamp = timeGen();

            // 1. 时钟回拨防御
            if (timestamp < lastTimestamp) {
                long offset = lastTimestamp - timestamp;
                if (offset <= maxBackwardMs) {
                    try {
                        Thread.sleep(offset);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    timestamp = timeGen();
                    if (timestamp < lastTimestamp) {
                        throwClockBackoffException(lastTimestamp - timestamp);
                    }
                } else {
                    throwClockBackoffException(offset);
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
        } finally {
            lock.unlock();
        }
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

    private void throwClockBackoffException(long offsetMs) {
        throw new BusinessException(CommonResultCode.INTERNAL_SERVER_ERROR,
                String.format("Clock moved backwards. Refusing to generate id for %d milliseconds", offsetMs));
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

    /**
     * 获取当前最大允许时钟回拨容忍时长（毫秒）
     *
     * @return 时钟回拨容忍毫秒数
     */
    public long getMaxBackwardMs() {
        return maxBackwardMs;
    }

    /**
     * 将雪花 ID 逆向反解为结构化不可变信息载体（采用当前实例的 epoch）
     *
     * @param id 待反解的雪花长整型 ID
     * @return 包含发号时间戳、Instant、LocalDateTime、数据中心、节点与序列号的结构化信息
     */
    public SnowflakeIdInfo parse(long id) {
        return parse(id, this.epoch);
    }

    // ==================== 静态工具与位段解析方法 ====================

    /**
     * 将雪花 ID 逆向反解为结构化不可变信息载体（指定基准时间戳 epoch）
     *
     * @param id    待反解的雪花长整型 ID
     * @param epoch 生成该 ID 所使用的基准时间戳（毫秒）
     * @return 包含发号时间戳、Instant、LocalDateTime、数据中心、节点与序列号的结构化信息
     */
    public static SnowflakeIdInfo parse(long id, long epoch) {
        long timestamp = getTimestamp(id, epoch);
        long datacenterId = getDatacenterId(id);
        long workerId = getWorkerId(id);
        long sequence = getSequence(id);
        Instant instant = Instant.ofEpochMilli(timestamp);
        LocalDateTime dateTime = LocalDateTime.ofInstant(instant, ZoneId.systemDefault());
        return new SnowflakeIdInfo(id, timestamp, instant, dateTime, datacenterId, workerId, sequence, epoch);
    }

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
     * 一次性平滑推导 workerId 与 datacenterId (0~31)
     * 1. JVM 系统属性/环境变量 -> 2. 网卡 MAC 地址哈希 -> 3. 本机 IP 地址低位哈希 -> 4. 随机数兜底
     * 单次遍历网卡与 IP 解析，杜绝重复网络 I/O，并防御 Math.abs 绝对值负溢出
     *
     * @return 推导得到的节点 ID 载体
     */
    private static NodeIds deriveNodeIds() {
        return deriveNodeIds(null, null);
    }

    private static NodeIds deriveNodeIds(Long explicitWorkerId, Long explicitDatacenterId) {
        Long workerId = explicitWorkerId;
        Long datacenterId = explicitDatacenterId;
        if (workerId == null) {
            workerId = parseIdProperty("ateng.boot.core.snowflake.worker-id", "ATENG_BOOT_CORE_SNOWFLAKE_WORKER_ID", MAX_WORKER_ID);
            if (workerId == null) {
                workerId = parseIdProperty("ateng.boot.snowflake.worker-id", "ATENG_BOOT_SNOWFLAKE_WORKER_ID", MAX_WORKER_ID);
            }
        }
        if (datacenterId == null) {
            datacenterId = parseIdProperty("ateng.boot.core.snowflake.datacenter-id", "ATENG_BOOT_CORE_SNOWFLAKE_DATACENTER_ID", MAX_DATACENTER_ID);
            if (datacenterId == null) {
                datacenterId = parseIdProperty("ateng.boot.snowflake.datacenter-id", "ATENG_BOOT_SNOWFLAKE_DATACENTER_ID", MAX_DATACENTER_ID);
            }
        }
        if (workerId != null && datacenterId != null) {
            return new NodeIds(workerId, datacenterId);
        }

        // 尝试从网卡 MAC 地址推导
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces != null && interfaces.hasMoreElements()) {
                NetworkInterface ni = interfaces.nextElement();
                if (!ni.isLoopback() && ni.isUp()) {
                    byte[] mac = ni.getHardwareAddress();
                    if (mac != null && mac.length > 0) {
                        if (workerId == null) {
                            int sum = 0;
                            for (byte b : mac) {
                                sum = (sum << 5) - sum + (b & 0xFF);
                            }
                            workerId = (long) ((sum & 0x7FFFFFFF) % (MAX_WORKER_ID + 1));
                        }
                        if (datacenterId == null) {
                            if (mac.length >= 2) {
                                datacenterId = (long) (((mac[0] & 0xFF) ^ (mac[1] & 0xFF)) % (MAX_DATACENTER_ID + 1));
                            } else {
                                datacenterId = (long) ((mac[0] & 0xFF) % (MAX_DATACENTER_ID + 1));
                            }
                        }
                        break;
                    }
                }
            }
        } catch (Exception ignored) {
        }

        // 尝试从本机 IP 推导
        if (workerId == null || datacenterId == null) {
            try {
                InetAddress localHost = InetAddress.getLocalHost();
                byte[] address = localHost.getAddress();
                if (address != null && address.length > 0) {
                    if (workerId == null) {
                        workerId = (long) ((address[address.length - 1] & 0xFF) % (MAX_WORKER_ID + 1));
                    }
                    if (datacenterId == null) {
                        if (address.length >= 2) {
                            datacenterId = (long) ((address[address.length - 2] & 0xFF) % (MAX_DATACENTER_ID + 1));
                        } else {
                            datacenterId = (long) ((address[0] & 0xFF) % (MAX_DATACENTER_ID + 1));
                        }
                    }
                }
            } catch (Exception ignored) {
            }
        }

        // 随机兜底
        if (workerId == null) {
            workerId = ThreadLocalRandom.current().nextLong(MAX_WORKER_ID + 1);
            log.warn("无法从环境变量、网卡 MAC 或本机 IP 推导 workerId，降级采用随机兜底值: {}", workerId);
        }
        if (datacenterId == null) {
            datacenterId = ThreadLocalRandom.current().nextLong(MAX_DATACENTER_ID + 1);
            log.warn("无法从环境变量、网卡 MAC 或本机 IP 推导 datacenterId，降级采用随机兜底值: {}", datacenterId);
        }

        return new NodeIds(workerId, datacenterId);
    }

    private static Long parseIdProperty(String sysProp, String envProp, long maxId) {
        String prop = System.getProperty(sysProp);
        if (prop == null || prop.isBlank()) {
            prop = System.getenv(envProp);
        }
        if (prop != null && !prop.isBlank()) {
            try {
                long id = Long.parseLong(prop.trim());
                if (id >= 0 && id <= maxId) {
                    return id;
                }
            } catch (NumberFormatException ignored) {
            }
        }
        return null;
    }
}
