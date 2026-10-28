package io.github.atengk.boot.rabbitmq.helper;

import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * RabbitMQ 死信消息元数据只读诊断工具
 * 负责从 AMQP 消息头复杂的 x-death 嵌套结构中安全提取死信来源队列、原交换机、死因、流转次数等诊断信息
 * 宽容兼容 String、byte[]、LongString 等异构驱动类型，杜绝 ClassCastException
 *
 * @author Ateng
 * @since 2026-10-21
 */
public final class RabbitDeadLetterHelper {

    private static final String KEY_QUEUE = "queue";
    private static final String KEY_EXCHANGE = "exchange";
    private static final String KEY_REASON = "reason";
    private static final String KEY_COUNT = "count";
    private static final String KEY_ROUTING_KEYS = "routing-keys";
    private static final String KEY_TIME = "time";

    private RabbitDeadLetterHelper() {
    }

    /**
     * 获取首个 (最近一次) 死信来源队列名称
     *
     * @param message AMQP 消息
     * @return 原始队列名称，不存在时返回 null
     */
    public static String getOriginalQueue(Message message) {
        return getOriginalQueue(extractProperties(message));
    }

    /**
     * 获取首个 (最近一次) 死信来源队列名称
     *
     * @param props 消息属性
     * @return 原始队列名称，不存在时返回 null
     */
    public static String getOriginalQueue(MessageProperties props) {
        DeadLetterRecord record = getFirstDeath(props);
        return record != null ? record.queue() : null;
    }

    /**
     * 获取首个 (最近一次) 死信来源交换机名称
     *
     * @param message AMQP 消息
     * @return 原始交换机名称，不存在时返回 null
     */
    public static String getOriginalExchange(Message message) {
        return getOriginalExchange(extractProperties(message));
    }

    /**
     * 获取首个 (最近一次) 死信来源交换机名称
     *
     * @param props 消息属性
     * @return 原始交换机名称，不存在时返回 null
     */
    public static String getOriginalExchange(MessageProperties props) {
        DeadLetterRecord record = getFirstDeath(props);
        return record != null ? record.exchange() : null;
    }

    /**
     * 获取死信原因 (例如 rejected、expired、maxlen)
     *
     * @param message AMQP 消息
     * @return 死信原因，不存在时返回 null
     */
    public static String getDeathReason(Message message) {
        return getDeathReason(extractProperties(message));
    }

    /**
     * 获取死信原因 (例如 rejected、expired、maxlen)
     *
     * @param props 消息属性
     * @return 死信原因，不存在时返回 null
     */
    public static String getDeathReason(MessageProperties props) {
        DeadLetterRecord record = getFirstDeath(props);
        return record != null ? record.reason() : null;
    }

    /**
     * 获取死信发生次数计数
     *
     * @param message AMQP 消息
     * @return 死信流转计数值，不存在时返回 0
     */
    public static long getDeathCount(Message message) {
        return getDeathCount(extractProperties(message));
    }

    /**
     * 获取死信发生次数计数
     *
     * @param props 消息属性
     * @return 死信流转计数值，不存在时返回 0
     */
    public static long getDeathCount(MessageProperties props) {
        DeadLetterRecord record = getFirstDeath(props);
        return record != null ? record.count() : 0L;
    }

    /**
     * 获取原始路由键列表
     *
     * @param message AMQP 消息
     * @return 原始路由键列表，不存在时保底返回空列表
     */
    public static List<String> getOriginalRoutingKeys(Message message) {
        return getOriginalRoutingKeys(extractProperties(message));
    }

    /**
     * 获取原始路由键列表
     *
     * @param props 消息属性
     * @return 原始路由键列表，不存在时保底返回空列表
     */
    public static List<String> getOriginalRoutingKeys(MessageProperties props) {
        DeadLetterRecord record = getFirstDeath(props);
        return record != null ? record.routingKeys() : Collections.emptyList();
    }

    /**
     * 获取首个 (最近一次) 结构化死信记录
     *
     * @param message AMQP 消息
     * @return 死信记录，不存在时返回 null
     */
    public static DeadLetterRecord getFirstDeath(Message message) {
        return getFirstDeath(extractProperties(message));
    }

    /**
     * 获取首个 (最近一次) 结构化死信记录
     *
     * @param props 消息属性
     * @return 死信记录，不存在时返回 null
     */
    public static DeadLetterRecord getFirstDeath(MessageProperties props) {
        List<DeadLetterRecord> all = getAllDeaths(props);
        return all.isEmpty() ? null : all.get(0);
    }

    /**
     * 获取全部死信流转记录列表 (支持多次在队列间转发的排障审计)
     *
     * @param message AMQP 消息
     * @return 全部死信记录列表，无记录时返回空列表
     */
    public static List<DeadLetterRecord> getAllDeaths(Message message) {
        return getAllDeaths(extractProperties(message));
    }

    /**
     * 获取全部死信流转记录列表
     *
     * @param props 消息属性
     * @return 全部死信记录列表，无记录时返回空列表
     */
    public static List<DeadLetterRecord> getAllDeaths(MessageProperties props) {
        if (props == null) {
            return Collections.emptyList();
        }
        List<Map<String, ?>> xDeath = props.getXDeathHeader();
        if (xDeath == null || xDeath.isEmpty()) {
            return Collections.emptyList();
        }

        return xDeath.stream()
                .filter(Objects::nonNull)
                .map(RabbitDeadLetterHelper::parseRecord)
                .filter(Objects::nonNull)
                .toList();
    }

    private static DeadLetterRecord parseRecord(Map<String, ?> map) {
        String queue = asString(map.get(KEY_QUEUE));
        String exchange = asString(map.get(KEY_EXCHANGE));
        String reason = asString(map.get(KEY_REASON));

        long count = 0L;
        Object countObj = map.get(KEY_COUNT);
        if (countObj instanceof Number num) {
            count = num.longValue();
        } else if (countObj != null) {
            try {
                count = Long.parseLong(String.valueOf(countObj));
            } catch (NumberFormatException ignored) {
            }
        }

        Date time = null;
        Object timeObj = map.get(KEY_TIME);
        if (timeObj instanceof Date d) {
            time = d;
        }

        List<String> routingKeys = Collections.emptyList();
        Object rkObj = map.get(KEY_ROUTING_KEYS);
        if (rkObj instanceof List<?> list) {
            routingKeys = list.stream()
                    .filter(Objects::nonNull)
                    .map(RabbitDeadLetterHelper::asString)
                    .toList();
        }

        return new DeadLetterRecord(queue, exchange, reason, count, routingKeys, time);
    }

    private static MessageProperties extractProperties(Message message) {
        return message != null ? message.getMessageProperties() : null;
    }

    private static String asString(Object obj) {
        if (obj == null) {
            return null;
        }
        if (obj instanceof String str) {
            return str;
        }
        if (obj instanceof byte[] bytes) {
            return new String(bytes, StandardCharsets.UTF_8);
        }
        return String.valueOf(obj);
    }

    /**
     * 单条结构化死信元数据强类型记录
     *
     * @param queue       原始死信队列名
     * @param exchange    原始交换机
     * @param reason      死信原因 (rejected, expired, maxlen)
     * @param count       流转次数计数
     * @param routingKeys 原始路由键列表
     * @param time        死信记录时间戳
     */
    public record DeadLetterRecord(
            String queue,
            String exchange,
            String reason,
            long count,
            List<String> routingKeys,
            Date time
    ) {
    }
}
