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

    /**
     * 死信队列主题前缀
     */
    public static final String DLQ_TOPIC_PREFIX = "%DLQ%";

    /**
     * 死信重放标记协议头（值为 "true"）
     */
    public static final String HEADER_DEAD_LETTER_REPLAYED = "X-Dead-Letter-Replayed";

    /**
     * 死信重放累计次数协议头（由 1 开始递增）
     */
    public static final String HEADER_DEAD_LETTER_REPLAY_COUNT = "X-Dead-Letter-Replay-Count";

    /**
     * 死信重放触发时间戳协议头（毫秒时间戳）
     */
    public static final String HEADER_DEAD_LETTER_REPLAY_TIME = "X-Replay-Time";

    /**
     * 死信来源消费组协议头
     */
    public static final String HEADER_DEAD_LETTER_CONSUMER_GROUP = "X-Dead-Letter-Consumer-Group";

    /**
     * 死信原始业务主题协议头
     */
    public static final String HEADER_DEAD_LETTER_ORIGINAL_TOPIC = "X-Dead-Letter-Original-Topic";

    /**
     * 死信原始消息 ID 协议头
     */
    public static final String HEADER_DEAD_LETTER_ORIGINAL_MESSAGE_ID = "X-Dead-Letter-Original-Message-Id";
}
