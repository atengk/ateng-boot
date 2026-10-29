package io.github.atengk.boot.rabbitmq.template;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Envelope;
import com.rabbitmq.client.GetResponse;
import io.github.atengk.boot.rabbitmq.properties.RabbitmqProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.ChannelCallback;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * RabbitDeadLetterReplayTemplate 死信受控治理门面单元测试
 * 验证死信重放、元数据清洗、防二次死信雪崩熔断、谓词过滤、只读探查与清空
 *
 * @author Ateng
 * @since 2026-10-29
 */
@DisplayName("RabbitDeadLetterReplayTemplate 死信受控重放单元测试")
class RabbitDeadLetterReplayTemplateTest {

    private RabbitTemplate rabbitTemplate;
    private Channel channel;
    private RabbitmqProperties properties;
    private RabbitDeadLetterReplayTemplate replayTemplate;

    @BeforeEach
    void setUp() {
        rabbitTemplate = mock(RabbitTemplate.class);
        channel = mock(Channel.class);
        properties = new RabbitmqProperties();
        properties.getDeadLetter().setDefaultReplayLimit(100);
        properties.getDeadLetter().setMaxReplayCount(1);

        replayTemplate = new RabbitDeadLetterReplayTemplate(rabbitTemplate, properties);

        // 模拟 rabbitTemplate.execute 直接执行 ChannelCallback
        when(rabbitTemplate.execute(any())).thenAnswer(invocation -> {
            ChannelCallback<?> callback = invocation.getArgument(0);
            return callback.doInRabbit(channel);
        });
    }

    @AfterEach
    void tearDown() {
        // 遵循上下文与资源零污染铁律
        reset(rabbitTemplate, channel);
    }

    @Test
    @DisplayName("死信受控重放：正常重放成功，剥离底层 x-death 头，注入 X-Dead-Letter-Replayed 与重放计数，并确认出队")
    void testReplaySuccess() throws IOException {
        String dlq = "sample.order.dlq";
        String targetExchange = "sample.order.direct";
        String targetRoutingKey = "order.create";

        // 构建模拟死信消息属性 (带有 x-death 头和业务头)
        Map<String, Object> headers = new HashMap<>();
        headers.put("bizKey", "ORD-20261029-001");
        headers.put("tenantId", "tenant-001");

        Map<String, Object> xDeathMap = new HashMap<>();
        xDeathMap.put("queue", "sample.order.origin.queue");
        xDeathMap.put("exchange", "sample.order.origin.exchange");
        xDeathMap.put("reason", "rejected");
        xDeathMap.put("count", 1L);
        headers.put("x-death", List.of(xDeathMap));

        AMQP.BasicProperties basicProps = new AMQP.BasicProperties.Builder()
                .headers(headers)
                .contentType("application/json")
                .correlationId("corr-12345")
                .build();
        Envelope envelope = new Envelope(101L, false, "origin.exchange", "origin.key");
        GetResponse getResponse = new GetResponse(envelope, basicProps, "{\"orderId\":\"1001\"}".getBytes(StandardCharsets.UTF_8), 0);

        // 模拟第一次获取到消息，第二次返回 null 队列已空
        when(channel.basicGet(eq(dlq), eq(false))).thenReturn(getResponse, (GetResponse) null);

        int count = replayTemplate.replay(dlq, targetExchange, targetRoutingKey, 10);

        assertThat(count).isEqualTo(1);
        verify(channel, times(1)).basicAck(eq(101L), eq(false));
        verify(channel, never()).basicNack(anyLong(), anyBoolean(), anyBoolean());

        ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);
        verify(rabbitTemplate, times(1)).send(eq(targetExchange), eq(targetRoutingKey), messageCaptor.capture());

        Message sentMessage = messageCaptor.getValue();
        MessageProperties sentProps = sentMessage.getMessageProperties();
        assertThat((Boolean) sentProps.getHeader(RabbitDeadLetterReplayTemplate.HEADER_X_DEAD_LETTER_REPLAYED)).isTrue();
        assertThat((Integer) sentProps.getHeader(RabbitDeadLetterReplayTemplate.HEADER_X_REPLAY_COUNT)).isEqualTo(1);
        assertThat((Object) sentProps.getHeader(RabbitDeadLetterReplayTemplate.HEADER_X_REPLAY_TIME)).isNotNull();
        assertThat((String) sentProps.getHeader(RabbitDeadLetterReplayTemplate.HEADER_X_ORIGINAL_DEAD_LETTER_QUEUE)).isEqualTo("sample.order.origin.queue");
        assertThat((String) sentProps.getHeader(RabbitDeadLetterReplayTemplate.HEADER_X_ORIGINAL_DEAD_LETTER_REASON)).isEqualTo("rejected");

        // 验证底层的 x-death 结构已被剥离，防止下游重复误判
        assertThat(sentProps.getHeaders().containsKey("x-death")).isFalse();
        // 验证业务 header 完整保留
        assertThat((String) sentProps.getHeader("bizKey")).isEqualTo("ORD-20261029-001");
        assertThat((String) sentProps.getHeader("tenantId")).isEqualTo("tenant-001");
    }

    @Test
    @DisplayName("对抗性测试：重放过程中 rabbitTemplate.send 抛出异常中断，当前拉取的消息确定性执行 basicNack(requeue=true) 补偿回滚")
    void testReplaySendExceptionRequeuesCurrentMessage() throws IOException {
        String dlq = "sample.order.dlq";
        String targetExchange = "sample.order.direct";
        String targetRoutingKey = "order.create";

        Envelope envelope = new Envelope(102L, false, "origin.exchange", "origin.key");
        AMQP.BasicProperties basicProps = new AMQP.BasicProperties.Builder()
                .headers(Map.of("bizKey", "FAIL-01"))
                .build();
        GetResponse getResponse = new GetResponse(envelope, basicProps, "fail".getBytes(StandardCharsets.UTF_8), 0);
        when(channel.basicGet(eq(dlq), eq(false))).thenReturn(getResponse);

        // 模拟投递时抛出网络或 Broker 异常
        doThrow(new AmqpException("Simulated broker network failure"))
                .when(rabbitTemplate).send(anyString(), anyString(), any(Message.class));

        assertThatThrownBy(() -> replayTemplate.replay(dlq, targetExchange, targetRoutingKey))
                .isInstanceOf(AmqpException.class)
                .hasMessageContaining("Simulated broker network failure");

        // 验证严禁 ACK
        verify(channel, never()).basicAck(anyLong(), anyBoolean());
        // 验证当前消息必须被确定性 basicNack(requeue=true) 回滚，杜绝 unacked 消息悬挂
        verify(channel, times(1)).basicNack(eq(102L), eq(false), eq(true));
    }

    @Test
    @DisplayName("防二次死信雪崩：消息重放次数已达上限时阻断重放，不发送至目标交换机，并将消息 requeue 回滚原队列")
    void testReplayAntiAvalancheBlocked() throws IOException {
        String dlq = "sample.order.dlq";
        String targetExchange = "sample.order.direct";
        String targetRoutingKey = "order.create";

        // 构建已重放过 1 次的消息属性 (达到 maxReplayCount = 1)
        Map<String, Object> headers = new HashMap<>();
        headers.put(RabbitDeadLetterReplayTemplate.HEADER_X_DEAD_LETTER_REPLAYED, true);
        headers.put(RabbitDeadLetterReplayTemplate.HEADER_X_REPLAY_COUNT, 1);
        headers.put("bizKey", "ORD-AVALANCHE-999");

        AMQP.BasicProperties basicProps = new AMQP.BasicProperties.Builder()
                .headers(headers)
                .contentType("application/json")
                .build();
        Envelope envelope = new Envelope(202L, false, "origin.exchange", "origin.key");
        GetResponse getResponse = new GetResponse(envelope, basicProps, "{\"orderId\":\"bad\"}".getBytes(StandardCharsets.UTF_8), 0);

        when(channel.basicGet(eq(dlq), eq(false))).thenReturn(getResponse, (GetResponse) null);

        int count = replayTemplate.replay(dlq, targetExchange, targetRoutingKey);

        // 重放成功计数为 0
        assertThat(count).isEqualTo(0);
        // 严禁发送网络报文
        verify(rabbitTemplate, never()).send(anyString(), anyString(), any(Message.class));
        // 严禁确认出队
        verify(channel, never()).basicAck(anyLong(), anyBoolean());
        // 必须 requeue 回死信队列保存现场
        verify(channel, times(1)).basicNack(eq(202L), eq(false), eq(true));
    }

    @Test
    @DisplayName("谓词过滤重放：仅重放匹配 Predicate 的消息，不匹配的消息安全 requeue 放回原队列")
    void testReplayWithPredicateFilter() throws IOException {
        String dlq = "sample.order.dlq";
        String targetExchange = "sample.order.direct";
        String targetRoutingKey = "order.create";

        // 第一条：匹配的消息 (bizKey = MATCH-01)
        Map<String, Object> headers1 = new HashMap<>();
        headers1.put("bizKey", "MATCH-01");
        AMQP.BasicProperties props1 = new AMQP.BasicProperties.Builder().headers(headers1).build();
        GetResponse res1 = new GetResponse(new Envelope(301L, false, "ex", "rk"), props1, "match".getBytes(StandardCharsets.UTF_8), 1);

        // 第二条：不匹配的消息 (bizKey = SKIP-02)
        Map<String, Object> headers2 = new HashMap<>();
        headers2.put("bizKey", "SKIP-02");
        AMQP.BasicProperties props2 = new AMQP.BasicProperties.Builder().headers(headers2).build();
        GetResponse res2 = new GetResponse(new Envelope(302L, false, "ex", "rk"), props2, "skip".getBytes(StandardCharsets.UTF_8), 0);

        when(channel.basicGet(eq(dlq), eq(false))).thenReturn(res1, res2, (GetResponse) null);

        int count = replayTemplate.replay(dlq, targetExchange, targetRoutingKey, 10,
                msg -> "MATCH-01".equals(msg.getMessageProperties().getHeader("bizKey")));

        assertThat(count).isEqualTo(1);
        verify(rabbitTemplate, times(1)).send(eq(targetExchange), eq(targetRoutingKey), any(Message.class));
        verify(channel, times(1)).basicAck(eq(301L), eq(false));
        // 不匹配的消息被回退
        verify(channel, times(1)).basicNack(eq(302L), eq(false), eq(true));
    }

    @Test
    @DisplayName("只读探查 peek：探测头部消息而不出队确认，所有探查的消息在 finally 块中逆序 basicNack(requeue=true) 维持 FIFO 原序")
    void testPeekWithoutAckAndMaintainsFifoOrder() throws IOException {
        String dlq = "sample.order.dlq";

        // 模拟两条消息被依次拉取
        GetResponse res1 = new GetResponse(new Envelope(401L, false, "ex", "rk"),
                new AMQP.BasicProperties.Builder().headers(Map.of("bizKey", "PEEK-01")).build(),
                "peek-payload-1".getBytes(StandardCharsets.UTF_8), 1);
        GetResponse res2 = new GetResponse(new Envelope(402L, false, "ex", "rk"),
                new AMQP.BasicProperties.Builder().headers(Map.of("bizKey", "PEEK-02")).build(),
                "peek-payload-2".getBytes(StandardCharsets.UTF_8), 0);

        when(channel.basicGet(eq(dlq), eq(false))).thenReturn(res1, res2, (GetResponse) null);

        List<Message> peeked = replayTemplate.peek(dlq, 5);

        assertThat(peeked).hasSize(2);
        assertThat(new String(peeked.get(0).getBody(), StandardCharsets.UTF_8)).isEqualTo("peek-payload-1");
        assertThat(new String(peeked.get(1).getBody(), StandardCharsets.UTF_8)).isEqualTo("peek-payload-2");

        // 严禁确认
        verify(channel, never()).basicAck(anyLong(), anyBoolean());
        // 严禁发送
        verify(rabbitTemplate, never()).send(anyString(), anyString(), any(Message.class));

        // 验证必须逆序执行 basicNack (先 402L，再 401L)，确保 401L 最终回到队头，维持严格 FIFO 顺序
        InOrder inOrder = inOrder(channel);
        inOrder.verify(channel).basicNack(eq(402L), eq(false), eq(true));
        inOrder.verify(channel).basicNack(eq(401L), eq(false), eq(true));
    }

    @Test
    @DisplayName("队列清空 purge：调用底层 channel.queuePurge 并返回清理总数")
    void testPurgeQueue() throws IOException {
        String dlq = "sample.order.dlq";
        AMQP.Queue.PurgeOk purgeOk = mock(AMQP.Queue.PurgeOk.class);
        when(purgeOk.getMessageCount()).thenReturn(42);
        when(channel.queuePurge(eq(dlq))).thenReturn(purgeOk);

        int purged = replayTemplate.purge(dlq);

        assertThat(purged).isEqualTo(42);
        verify(channel, times(1)).queuePurge(eq(dlq));
    }

    @Test
    @DisplayName("前置卫语句与快速失败校验：非法入参抛出 IllegalArgumentException")
    void testFailFastValidations() {
        assertThatThrownBy(() -> replayTemplate.replay("", "ex", "rk"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("dlq");

        assertThatThrownBy(() -> replayTemplate.replay("dlq", "", "rk"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("targetExchange");

        assertThatThrownBy(() -> replayTemplate.replay("dlq", "ex", "rk", 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("limit");

        assertThatThrownBy(() -> replayTemplate.replay("dlq", "ex", "rk", 1001))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("limit");

        assertThatThrownBy(() -> replayTemplate.peek("", 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("dlq");

        assertThatThrownBy(() -> replayTemplate.peek("dlq", 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("limit");

        assertThatThrownBy(() -> replayTemplate.purge(""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("dlq");
    }
}
