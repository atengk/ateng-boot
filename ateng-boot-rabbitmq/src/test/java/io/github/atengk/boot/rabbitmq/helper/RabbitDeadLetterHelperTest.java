package io.github.atengk.boot.rabbitmq.helper;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;

import java.nio.charset.StandardCharsets;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RabbitDeadLetterHelper 死信诊断助手单元测试
 *
 * @author Ateng
 * @since 2026-10-21
 */
@DisplayName("RabbitDeadLetterHelper 死信诊断助手测试")
class RabbitDeadLetterHelperTest {

    @Test
    @DisplayName("标准 String 类型 x-death 消息头提取全字段")
    void testExtractFromStandardStringXDeath() {
        Date deathTime = new Date();
        Map<String, Object> deathMap = new HashMap<>();
        deathMap.put("queue", "order.create.queue");
        deathMap.put("exchange", "order.direct");
        deathMap.put("reason", "rejected");
        deathMap.put("count", 3L);
        deathMap.put("routing-keys", List.of("order.create"));
        deathMap.put("time", deathTime);

        MessageProperties props = new MessageProperties();
        props.setHeader("x-death", List.of(deathMap));
        Message message = new Message("{}".getBytes(StandardCharsets.UTF_8), props);

        assertThat(RabbitDeadLetterHelper.getOriginalQueue(message)).isEqualTo("order.create.queue");
        assertThat(RabbitDeadLetterHelper.getOriginalExchange(message)).isEqualTo("order.direct");
        assertThat(RabbitDeadLetterHelper.getDeathReason(message)).isEqualTo("rejected");
        assertThat(RabbitDeadLetterHelper.getDeathCount(message)).isEqualTo(3L);
        assertThat(RabbitDeadLetterHelper.getOriginalRoutingKeys(message)).containsExactly("order.create");

        RabbitDeadLetterHelper.DeadLetterRecord record = RabbitDeadLetterHelper.getFirstDeath(message);
        assertThat(record).isNotNull();
        assertThat(record.queue()).isEqualTo("order.create.queue");
        assertThat(record.exchange()).isEqualTo("order.direct");
        assertThat(record.reason()).isEqualTo("rejected");
        assertThat(record.count()).isEqualTo(3L);
        assertThat(record.routingKeys()).containsExactly("order.create");
        assertThat(record.time()).isEqualTo(deathTime);
    }

    @Test
    @DisplayName("RabbitMQ 底层驱动 byte[] 异构类型安全解码")
    void testExtractFromHeterogeneousByteTypes() {
        Date now = new Date();
        Map<String, Object> deathMap = new HashMap<>();
        deathMap.put("queue", "payment.queue".getBytes(StandardCharsets.UTF_8));
        deathMap.put("exchange", "payment.direct".getBytes(StandardCharsets.UTF_8));
        deathMap.put("reason", "expired".getBytes(StandardCharsets.UTF_8));
        deathMap.put("count", 1); // Integer 类型
        deathMap.put("routing-keys", List.of("pay.alipay".getBytes(StandardCharsets.UTF_8)));
        deathMap.put("time", now);

        MessageProperties props = new MessageProperties();
        props.setHeader("x-death", List.of(deathMap));
        Message message = new Message("{}".getBytes(StandardCharsets.UTF_8), props);

        assertThat(RabbitDeadLetterHelper.getOriginalQueue(message)).isEqualTo("payment.queue");
        assertThat(RabbitDeadLetterHelper.getOriginalExchange(message)).isEqualTo("payment.direct");
        assertThat(RabbitDeadLetterHelper.getDeathReason(message)).isEqualTo("expired");
        assertThat(RabbitDeadLetterHelper.getDeathCount(message)).isEqualTo(1L);
        assertThat(RabbitDeadLetterHelper.getOriginalRoutingKeys(message)).containsExactly("pay.alipay");

        RabbitDeadLetterHelper.DeadLetterRecord record = RabbitDeadLetterHelper.getFirstDeath(props);
        assertThat(record).isNotNull();
        assertThat(record.queue()).isEqualTo("payment.queue");
        assertThat(record.exchange()).isEqualTo("payment.direct");
        assertThat(record.reason()).isEqualTo("expired");
        assertThat(record.count()).isEqualTo(1L);
        assertThat(record.routingKeys()).containsExactly("pay.alipay");
    }

    @Test
    @DisplayName("多重死信流转历史完整审计 (getAllDeaths)")
    void testMultipleDeathsHistory() {
        Map<String, Object> death1 = Map.of(
                "queue", "retry.queue",
                "exchange", "retry.exchange",
                "reason", "expired",
                "count", 2L
        );
        Map<String, Object> death2 = Map.of(
                "queue", "origin.biz.queue",
                "exchange", "biz.exchange",
                "reason", "rejected",
                "count", 1L
        );

        MessageProperties props = new MessageProperties();
        props.setHeader("x-death", List.of(death1, death2));
        Message message = new Message(new byte[0], props);

        List<RabbitDeadLetterHelper.DeadLetterRecord> all = RabbitDeadLetterHelper.getAllDeaths(message);
        assertThat(all).hasSize(2);
        assertThat(all.get(0).queue()).isEqualTo("retry.queue");
        assertThat(all.get(0).reason()).isEqualTo("expired");
        assertThat(all.get(1).queue()).isEqualTo("origin.biz.queue");
        assertThat(all.get(1).reason()).isEqualTo("rejected");

        // getFirstDeath 返回第一条 (最近一次)
        RabbitDeadLetterHelper.DeadLetterRecord first = RabbitDeadLetterHelper.getFirstDeath(message);
        assertThat(first).isNotNull();
        assertThat(first.queue()).isEqualTo("retry.queue");
    }

    @Test
    @DisplayName("空消息或无 x-death 头时的防御性安全返回")
    void testEmptyOrNullXDeathSafely() {
        // 1. null 消息与 null 属性
        assertThat(RabbitDeadLetterHelper.getOriginalQueue((Message) null)).isNull();
        assertThat(RabbitDeadLetterHelper.getOriginalExchange((Message) null)).isNull();
        assertThat(RabbitDeadLetterHelper.getDeathReason((Message) null)).isNull();
        assertThat(RabbitDeadLetterHelper.getDeathCount((Message) null)).isZero();
        assertThat(RabbitDeadLetterHelper.getOriginalRoutingKeys((Message) null)).isEmpty();
        assertThat(RabbitDeadLetterHelper.getFirstDeath((Message) null)).isNull();
        assertThat(RabbitDeadLetterHelper.getAllDeaths((Message) null)).isEmpty();

        assertThat(RabbitDeadLetterHelper.getOriginalQueue((MessageProperties) null)).isNull();
        assertThat(RabbitDeadLetterHelper.getOriginalExchange((MessageProperties) null)).isNull();
        assertThat(RabbitDeadLetterHelper.getDeathReason((MessageProperties) null)).isNull();
        assertThat(RabbitDeadLetterHelper.getDeathCount((MessageProperties) null)).isZero();
        assertThat(RabbitDeadLetterHelper.getOriginalRoutingKeys((MessageProperties) null)).isEmpty();
        assertThat(RabbitDeadLetterHelper.getFirstDeath((MessageProperties) null)).isNull();
        assertThat(RabbitDeadLetterHelper.getAllDeaths((MessageProperties) null)).isEmpty();

        // 2. 空属性与无 x-death
        Message emptyMessage = new Message(new byte[0], new MessageProperties());
        assertThat(RabbitDeadLetterHelper.getOriginalQueue(emptyMessage)).isNull();
        assertThat(RabbitDeadLetterHelper.getOriginalExchange(emptyMessage)).isNull();
        assertThat(RabbitDeadLetterHelper.getDeathReason(emptyMessage)).isNull();
        assertThat(RabbitDeadLetterHelper.getDeathCount(emptyMessage)).isZero();
        assertThat(RabbitDeadLetterHelper.getOriginalRoutingKeys(emptyMessage)).isEmpty();
        assertThat(RabbitDeadLetterHelper.getFirstDeath(emptyMessage)).isNull();
        assertThat(RabbitDeadLetterHelper.getAllDeaths(emptyMessage)).isEmpty();

        // 3. x-death 为空列表
        MessageProperties props = new MessageProperties();
        props.setHeader("x-death", Collections.emptyList());
        Message emptyListMessage = new Message(new byte[0], props);
        assertThat(RabbitDeadLetterHelper.getFirstDeath(emptyListMessage)).isNull();
        assertThat(RabbitDeadLetterHelper.getAllDeaths(emptyListMessage)).isEmpty();
    }

    @Test
    @DisplayName("异常 count 格式容错与解析降级")
    void testAbnormalCountFormatFallback() {
        Map<String, Object> map1 = Map.of(
                "queue", "q1",
                "count", "99" // String 格式的数字
        );
        Map<String, Object> map2 = Map.of(
                "queue", "q2",
                "count", "not-a-number" // 非法字符串
        );

        MessageProperties props = new MessageProperties();
        props.setHeader("x-death", List.of(map1, map2));
        Message message = new Message(new byte[0], props);

        List<RabbitDeadLetterHelper.DeadLetterRecord> records = RabbitDeadLetterHelper.getAllDeaths(message);
        assertThat(records.get(0).count()).isEqualTo(99L);
        assertThat(records.get(1).count()).isZero();
    }
}
