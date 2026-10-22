package io.github.atengk.boot.rocketmq.constant;

/**
 * RocketMQ 核心常量规约
 *
 * @author Ateng
 * @since 2026-10-22
 */
public final class RocketmqConstants {

    private RocketmqConstants() {
    }

    /**
     * 统一 MQ 类型标识
     */
    public static final String MQ_TYPE = "rocketmq";

    /**
     * 压缩协议头标识
     */
    public static final String HEADER_COMPRESSION = "X-Compression";

    /**
     * GZIP 压缩算法标识
     */
    public static final String COMPRESSION_GZIP = "gzip";

    /**
     * 默认通配符 Tag
     */
    public static final String TAG_ALL = "*";

    /**
     * Topic 命名正则表达式（只允许英文字母、数字、中划线、下划线及百分号）
     */
    public static final String TOPIC_REGEX = "^[%a-zA-Z0-9_-]+$";

    /**
     * Topic 命名正则 Pattern 预编译实例
     */
    public static final java.util.regex.Pattern TOPIC_PATTERN = java.util.regex.Pattern.compile(TOPIC_REGEX);
}
