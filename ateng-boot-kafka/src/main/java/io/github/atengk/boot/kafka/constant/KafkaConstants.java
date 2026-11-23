package io.github.atengk.boot.kafka.constant;

import io.github.atengk.boot.core.constant.MessageHeaderConstants;

import java.util.Locale;
import java.util.Set;

/**
 * Kafka 基础设施专用常量表
 *
 * @author Ateng
 * @since 2026-10-22
 */
public final class KafkaConstants {

    private KafkaConstants() {
    }

    /**
     * 框架保留的系统上下文与幂等消息头集合（小写归一化，禁止业务自定义头覆盖或作为扩展头误识别）
     */
    public static final Set<String> RESERVED_SYSTEM_HEADER_KEYS = Set.of(
            MessageHeaderConstants.TRACE_ID.toLowerCase(Locale.ROOT),
            MessageHeaderConstants.TENANT_ID.toLowerCase(Locale.ROOT),
            MessageHeaderConstants.USER_ID.toLowerCase(Locale.ROOT),
            MessageHeaderConstants.IGNORE_TENANT.toLowerCase(Locale.ROOT),
            MessageHeaderConstants.SEND_TIMESTAMP.toLowerCase(Locale.ROOT),
            MessageHeaderConstants.BIZ_KEY.toLowerCase(Locale.ROOT)
    );

    /**
     * 判断消息头键名是否为框架保留的系统上下文与幂等头（忽略大小写）
     *
     * @param headerKey 消息头键名
     * @return 属于系统保留头返回 true
     */
    public static boolean isReservedSystemHeader(String headerKey) {
        return headerKey != null && !headerKey.isBlank()
                && RESERVED_SYSTEM_HEADER_KEYS.contains(headerKey.trim().toLowerCase(Locale.ROOT));
    }

    /**
     * 配置属性前缀
     */
    public static final String PROPERTY_PREFIX = "ateng.boot.kafka";

    /**
     * 默认单条消费监听器容器工厂 Bean 名称
     */
    public static final String DEFAULT_CONTAINER_FACTORY = "kafkaListenerContainerFactory";

    /**
     * 专用批量消费监听器容器工厂 Bean 名称
     */
    public static final String BATCH_CONTAINER_FACTORY = "batchKafkaListenerContainerFactory";

    /**
     * 默认死信 Topic 后缀
     */
    public static final String DEFAULT_DLT_SUFFIX = ".DLT";

    /**
     * 发送端结构化日志宏标签
     */
    public static final String LOG_SEND_PREFIX = "[MQ-SEND]";

    /**
     * 消费端结构化日志宏标签
     */
    public static final String LOG_CONSUME_PREFIX = "[MQ-CONSUME]";

    /**
     * 批量消费端结构化日志宏标签
     */
    public static final String LOG_CONSUME_BATCH_PREFIX = "[MQ-CONSUME-BATCH]";

    /**
     * 死信转移结构化日志宏标签
     */
    public static final String LOG_DLT_FORWARD_PREFIX = "[MQ-DLT-FORWARD]";

    /**
     * 消费幂等重复结构化日志宏标签
     */
    public static final String LOG_IDEMPOTENT_DUPLICATE_PREFIX = "[MQ-IDEMPOTENT-DUPLICATE]";

    /**
     * 事务提交后发信失败高危告警宏标签
     */
    public static final String LOG_TX_POST_COMMIT_FAILED_PREFIX = "[MQ-TRANSACTION-POST-COMMIT-FAILED]";
}
