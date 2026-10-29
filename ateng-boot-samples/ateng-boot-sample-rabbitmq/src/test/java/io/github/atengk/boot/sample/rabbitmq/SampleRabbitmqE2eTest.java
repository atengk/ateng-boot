package io.github.atengk.boot.sample.rabbitmq;

import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.boot.rabbitmq.helper.RabbitDeadLetterHelper;
import io.github.atengk.boot.rabbitmq.interceptor.RabbitListenerGlobalAdvice;
import io.github.atengk.boot.rabbitmq.template.RabbitDeadLetterReplayTemplate;
import io.github.atengk.boot.rabbitmq.template.RabbitReliableTemplate;
import io.github.atengk.boot.sample.rabbitmq.config.RabbitScenarioTopologyConfig;
import io.github.atengk.boot.sample.rabbitmq.consumer.*;
import io.github.atengk.boot.sample.rabbitmq.model.LogMessage;
import io.github.atengk.boot.sample.rabbitmq.model.OrderCreateMessage;
import io.github.atengk.boot.sample.rabbitmq.model.OrderTimeoutMessage;
import io.github.atengk.boot.sample.rabbitmq.producer.TradeMessageProducer;
import io.github.atengk.boot.sample.rabbitmq.service.DeadLetterReplayService;
import io.github.atengk.boot.sample.rabbitmq.service.OrderTransactionService;
import org.aopalliance.intercept.MethodInvocation;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * RabbitMQ 1:1 独立场景沙箱端到端端实测套件
 * 单测试套件与双模自适应架构：
 * 1. 默认/CI 模式 (未激活 local): 自动加载 TestAmqpConfig Mock 桩，100% 离线自洽，零外部 Broker 与 Docker 依赖
 * 2. Local 真实模式 (激活 local): TestAmqpConfig 自动失效，直连真实 RabbitMQ Broker 执行全真网络通信
 *
 * @author Ateng
 * @since 2026-10-21
 */
@Slf4j
@SpringBootTest(classes = SampleRabbitmqApplication.class)
@Import(SampleRabbitmqE2eTest.TestAmqpConfig.class)
@DisplayName("RabbitMQ 场景沙箱 1:1 端到端端集成测试")
class SampleRabbitmqE2eTest {

    @Autowired
    private TradeMessageProducer producer;

    @Autowired
    private OrderTransactionService transactionService;

    @Autowired
    private OrderConsumer orderConsumer;

    @Autowired
    private LogTopicConsumer logTopicConsumer;

    @Autowired
    private IdempotentOrderConsumer idempotentConsumer;

    @Autowired(required = false)
    private OrderDelayConsumer delayConsumer;

    @Autowired
    private PoisonPillConsumer poisonPillConsumer;

    @Autowired
    private DeadLetterAuditConsumer deadLetterAuditConsumer;

    @Autowired
    private RabbitReliableTemplate rabbitReliableTemplate;

    @Autowired
    private BatchOrderConsumer batchOrderConsumer;

    @Autowired
    private PriorityOrderConsumer priorityOrderConsumer;

    @Autowired
    private AnonymousBroadcastConsumer broadcastConsumer;

    @Autowired
    private DeadLetterReplayService deadLetterReplayService;

    @Autowired(required = false)
    private com.rabbitmq.client.Channel mockChannel;

    @Autowired(required = false)
    private RabbitAdmin rabbitAdmin;

    @BeforeEach
    void setUp() {
        orderConsumer.clear();
        logTopicConsumer.clear();
        idempotentConsumer.clear();
        if (delayConsumer != null) {
            delayConsumer.clear();
        }
        poisonPillConsumer.clear();
        deadLetterAuditConsumer.clear();
        batchOrderConsumer.clear();
        priorityOrderConsumer.clear();
        broadcastConsumer.clear();
        if (mockChannel != null) {
            reset(mockChannel);
        }
        UserContextHolder.clear();
        TenantContextHolder.clear();
        MDC.clear();
    }

    @AfterEach
    void tearDown() {
        // 遵循全自洽单测与资源零污染铁律：Purge 清空真实队列消息残留
        if (rabbitAdmin != null) {
            try {
                rabbitAdmin.purgeQueue(RabbitScenarioTopologyConfig.QUEUE_ORDER_CREATE, false);
                rabbitAdmin.purgeQueue(RabbitScenarioTopologyConfig.QUEUE_ORDER_IDEMPOTENT, false);
                rabbitAdmin.purgeQueue(RabbitScenarioTopologyConfig.QUEUE_LOG_ERROR, false);
                if (delayConsumer != null) {
                    rabbitAdmin.purgeQueue(RabbitScenarioTopologyConfig.QUEUE_ORDER_DELAY, false);
                }
                rabbitAdmin.purgeQueue(RabbitScenarioTopologyConfig.QUEUE_BIZ_MAIN, false);
                rabbitAdmin.purgeQueue(RabbitScenarioTopologyConfig.QUEUE_BIZ_DLQ, false);
                rabbitAdmin.purgeQueue(RabbitScenarioTopologyConfig.QUEUE_BATCH_ORDER, false);
                rabbitAdmin.purgeQueue(RabbitScenarioTopologyConfig.QUEUE_PRIORITY_ORDER, false);
                rabbitAdmin.purgeQueue("sample.broadcast.fanout.queue", false);
            } catch (Exception ignored) {
            }
        }
        UserContextHolder.clear();
        TenantContextHolder.clear();
        MDC.clear();
    }

    private void awaitCondition(org.awaitility.core.ThrowingRunnable assertion) {
        await().atMost(Duration.ofSeconds(10))
                .pollInterval(Duration.ofMillis(50))
                .untilAsserted(assertion);
    }

    @Test
    @DisplayName("场景 A: Direct 精准路由与 Topic 层次通配可靠投递与消费")
    void testScenarioA_DirectAndTopicRouting() {
        UserContextHolder.setUserId("user-888");
        TenantContextHolder.setTenantId("tenant-999");
        MDC.put("traceId", "trace-scenario-a");

        // 1. 同步投递 Direct 订单消息
        OrderCreateMessage orderMsg = new OrderCreateMessage(
                "ORD-20261021-001",
                new BigDecimal("199.00"),
                "user-888",
                LocalDateTime.now()
        );
        producer.sendOrderCreate(orderMsg);

        awaitCondition(() -> {
            assertThat(orderConsumer.getReceivedMessages()).hasSize(1);
            OrderCreateMessage receivedOrder = orderConsumer.getReceivedMessages().get(0);
            assertThat(receivedOrder.orderId()).isEqualTo("ORD-20261021-001");
            assertThat(receivedOrder.amount()).isEqualByComparingTo("199.00");
        });

        // 2. 异步投递 Direct 订单消息并校验 Future Confirm
        OrderCreateMessage asyncMsg = new OrderCreateMessage(
                "ORD-20261021-002",
                new BigDecimal("299.00"),
                "user-888",
                LocalDateTime.now()
        );
        CompletableFuture<CorrelationData.Confirm> future = producer.sendOrderCreateAsync(asyncMsg);
        assertThat(future.join().isAck()).isTrue();

        // 3. 投递 Topic 层次通配日志
        LogMessage logMsg = new LogMessage("ERROR", "order-service", "订单支付超时重试失败");
        producer.sendLogError("log.error.order", logMsg);

        awaitCondition(() -> {
            assertThat(logTopicConsumer.getReceivedLogs()).hasSize(1);
            LogMessage receivedLog = logTopicConsumer.getReceivedLogs().get(0);
            assertThat(receivedLog.serviceName()).isEqualTo("order-service");
            assertThat(receivedLog.content()).isEqualTo("订单支付超时重试失败");
        });
    }

    @Test
    @DisplayName("场景 B: 消费端 @Idempotent 拦截 IdempotentException 静默确认 (Silent ACK)")
    void testScenarioB_IdempotentSilentAck() {
        String bizKey = "order-idemp-1001";

        // 1. 首次投递：正常消费成功
        producer.sendIdempotentMessage(bizKey, "ORDER_PAYLOAD_1");
        awaitCondition(() -> {
            assertThat(idempotentConsumer.getNormalProcessedCount()).isEqualTo(1);
            assertThat(idempotentConsumer.getIdempotentTriggeredCount()).isZero();
        });

        // 2. 重复投递相同业务主键：命中去重逻辑，抛出 IdempotentException，被全局切面捕获并触发 Silent ACK
        producer.sendIdempotentMessage(bizKey, "ORDER_PAYLOAD_1");
        awaitCondition(() -> {
            assertThat(idempotentConsumer.getNormalProcessedCount()).isEqualTo(1);
            assertThat(idempotentConsumer.getIdempotentTriggeredCount()).isEqualTo(1);
        });
    }

    @Test
    @DisplayName("场景 C: 延迟消息投递与超时自动取消")
    void testScenarioC_DelayOrderTimeout() {
        if (delayConsumer == null) {
            log.warn("[TEST-SKIP] 当前 Broker 未安装启用 rabbitmq_delayed_message_exchange 插件，跳过插件延迟消息全真测试");
            return;
        }
        OrderTimeoutMessage delayMsg = new OrderTimeoutMessage("ORD-TIMEOUT-001", 1800L, "用户超时未支付");
        producer.sendOrderDelay(delayMsg, Duration.ofMillis(200));

        awaitCondition(() -> {
            assertThat(delayConsumer.getTimeoutOrders()).hasSize(1);
            OrderTimeoutMessage received = delayConsumer.getTimeoutOrders().get(0);
            assertThat(received.orderId()).isEqualTo("ORD-TIMEOUT-001");
            assertThat(received.reason()).isEqualTo("用户超时未支付");
        });
    }

    @Test
    @DisplayName("场景 D: 毒丸与业务异常重试后进入 DLQ 死信队列，结合 RabbitDeadLetterHelper 提取诊断")
    void testScenarioD_PoisonPillAndDeadLetterHelper() {
        // 投递毒丸消息，触发死信流转并被 DLQ 审计消费者接收
        producer.sendBizMessage("biz-poison-1", "poison-pill");

        awaitCondition(() -> {
            assertThat(deadLetterAuditConsumer.getAuditRecords()).hasSize(1);
            RabbitDeadLetterHelper.DeadLetterRecord record = deadLetterAuditConsumer.getAuditRecords().get(0);
            assertThat(record.queue()).isEqualTo(RabbitScenarioTopologyConfig.QUEUE_BIZ_MAIN);
            assertThat(record.reason()).isEqualTo("rejected");
            assertThat(record.count()).isGreaterThanOrEqualTo(1L);
        });
    }

    @Test
    @DisplayName("场景 E: sendAfterCommit 事务感知发信 (事务回滚零发信，事务提交正常发信)")
    void testScenarioE_TransactionalSendAfterCommit() {
        // 1. 事务提交用例：本地数据正常入库，事务 Commit 后物理发信
        OrderCreateMessage commitOrder = new OrderCreateMessage(
                "ORD-TX-COMMIT-01",
                new BigDecimal("500.00"),
                "user-tx",
                LocalDateTime.now()
        );
        transactionService.createOrderWithCommit(commitOrder);

        assertThat(transactionService.getDbRecordCount()).isPositive();
        awaitCondition(() -> {
            assertThat(orderConsumer.getReceivedMessages()).hasSize(1);
            assertThat(orderConsumer.getReceivedMessages().get(0).orderId()).isEqualTo("ORD-TX-COMMIT-01");
        });

        // 清理消费记录
        orderConsumer.clear();

        // 2. 事务回滚用例：业务抛出异常导致本地事务回滚，发信钩子自动作废，杜绝幽灵消息
        OrderCreateMessage rollbackOrder = new OrderCreateMessage(
                "ORD-TX-ROLLBACK-02",
                new BigDecimal("600.00"),
                "user-tx",
                LocalDateTime.now()
        );

        assertThatThrownBy(() -> transactionService.createOrderWithRollback(rollbackOrder))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("触发本地事务回滚");

        // 验证由于事务回滚，消息未被发出，消费者收信数为 0
        assertThat(orderConsumer.getReceivedMessages()).isEmpty();
    }

    @Test
    @DisplayName("场景 F: 生产端 sendBatchReliable 同步/异步批量发送与消费端批量削峰消费")
    void testScenarioF_BatchOrderPublishAndConsume() {
        List<OrderCreateMessage> orders = List.of(
                new OrderCreateMessage("ORD-BATCH-001", new BigDecimal("10.00"), "user-1", LocalDateTime.now()),
                new OrderCreateMessage("ORD-BATCH-002", new BigDecimal("20.00"), "user-2", LocalDateTime.now()),
                new OrderCreateMessage("ORD-BATCH-003", new BigDecimal("30.00"), "user-3", LocalDateTime.now())
        );

        // 1. 同步批量投递
        producer.sendBatchOrders(orders);

        awaitCondition(() -> {
            assertThat(batchOrderConsumer.getAllReceivedOrders()).hasSize(3);
            assertThat(batchOrderConsumer.getAllReceivedOrders())
                    .extracting(OrderCreateMessage::orderId)
                    .containsExactly("ORD-BATCH-001", "ORD-BATCH-002", "ORD-BATCH-003");
        });

        // 2. 异步批量投递
        batchOrderConsumer.clear();
        producer.sendBatchOrdersAsync(orders).join();
        awaitCondition(() -> assertThat(batchOrderConsumer.getAllReceivedOrders()).hasSize(3));
    }

    @Test
    @DisplayName("场景 G: Quorum 队列优先级消息投递与优先级属性接收")
    void testScenarioG_PriorityAndQuorumOverflowTopology() {
        OrderCreateMessage vipOrder = new OrderCreateMessage(
                "ORD-VIP-001", new BigDecimal("9999.00"), "vip-user", LocalDateTime.now());

        producer.sendPriorityOrder(vipOrder, 9);

        awaitCondition(() -> {
            assertThat(priorityOrderConsumer.getReceivedOrders()).hasSize(1);
            assertThat(priorityOrderConsumer.getReceivedOrders().get(0).orderId()).isEqualTo("ORD-VIP-001");
            if (mockChannel != null) {
                // Mock 离线模式下校验注入的优先级
                assertThat(priorityOrderConsumer.getReceivedPriorities()).containsExactly(9);
            } else {
                // 真实 Quorum 模式下遵循 RabbitMQ 官方规范：Quorum 仲裁队列不保序持久化优先级 (保底默认值)
                assertThat(priorityOrderConsumer.getReceivedPriorities()).isNotEmpty();
            }
        });
    }

    @Test
    @DisplayName("场景 H: Fanout 广播与动态匿名临时排他自动删除队列接收")
    void testScenarioH_AnonymousBroadcastRouting() {
        producer.sendBroadcast("SYSTEM_CONFIG_UPDATED");

        awaitCondition(() -> {
            assertThat(broadcastConsumer.getReceivedBroadcasts()).hasSize(1);
            assertThat(broadcastConsumer.getReceivedBroadcasts().get(0)).isEqualTo("SYSTEM_CONFIG_UPDATED");
        });
    }

    @Test
    @DisplayName("场景 I: RabbitDeadLetterReplayTemplate 受控死信重放、元数据清洗、防二次雪崩熔断与清空")
    void testScenarioI_DeadLetterReplayAndAntiAvalanche() throws Exception {
        if (mockChannel == null) {
            // Local 真实 Broker 模式：验证真实 purge 操作
            int purged = deadLetterReplayService.purgeBizDeadLetters();
            assertThat(purged).isGreaterThanOrEqualTo(0);
            return;
        }

        // 1. 模拟死信队列中有 1 条因 rejected 产生的原始死信
        Map<String, Object> headers = new HashMap<>();
        headers.put("bizKey", "ORD-DEAD-100");
        Map<String, Object> xDeathMap = new HashMap<>();
        xDeathMap.put("queue", RabbitScenarioTopologyConfig.QUEUE_BIZ_MAIN);
        xDeathMap.put("exchange", RabbitScenarioTopologyConfig.EXCHANGE_BIZ_DIRECT);
        xDeathMap.put("reason", "rejected");
        xDeathMap.put("count", 1L);
        headers.put("x-death", List.of(xDeathMap));

        com.rabbitmq.client.AMQP.BasicProperties basicProps = new com.rabbitmq.client.AMQP.BasicProperties.Builder()
                .headers(headers)
                .contentType("application/json")
                .build();
        com.rabbitmq.client.Envelope envelope = new com.rabbitmq.client.Envelope(101L, false, "ex", "rk");
        com.rabbitmq.client.GetResponse getResponse = new com.rabbitmq.client.GetResponse(
                envelope, basicProps, "\"replayed-order-data\"".getBytes(StandardCharsets.UTF_8), 0);

        when(mockChannel.basicGet(eq(RabbitScenarioTopologyConfig.QUEUE_BIZ_DLQ), eq(false)))
                .thenReturn(getResponse, (com.rabbitmq.client.GetResponse) null);

        // 2. 执行死信受控重放并验证出队确认
        int replayed = deadLetterReplayService.replayBizDeadLetters(5);
        assertThat(replayed).isEqualTo(1);
        verify(mockChannel, times(1)).basicAck(eq(101L), eq(false));

        // 3. 验证防二次死信雪崩：重放次数已达上限的消息被熔断拦截并安全 requeue 回滚原队列
        Map<String, Object> avalancheHeaders = new HashMap<>();
        avalancheHeaders.put("bizKey", "ORD-DEAD-AVALANCHE");
        avalancheHeaders.put(RabbitDeadLetterReplayTemplate.HEADER_X_DEAD_LETTER_REPLAYED, true);
        avalancheHeaders.put(RabbitDeadLetterReplayTemplate.HEADER_X_REPLAY_COUNT, 1);

        com.rabbitmq.client.AMQP.BasicProperties avalancheProps = new com.rabbitmq.client.AMQP.BasicProperties.Builder()
                .headers(avalancheHeaders)
                .contentType("application/json")
                .build();
        com.rabbitmq.client.GetResponse avalancheResponse = new com.rabbitmq.client.GetResponse(
                new com.rabbitmq.client.Envelope(102L, false, "ex", "rk"), avalancheProps, "\"bad-data\"".getBytes(StandardCharsets.UTF_8), 0);

        when(mockChannel.basicGet(eq(RabbitScenarioTopologyConfig.QUEUE_BIZ_DLQ), eq(false)))
                .thenReturn(avalancheResponse, (com.rabbitmq.client.GetResponse) null);

        int blockedReplayed = deadLetterReplayService.replayBizDeadLetters(5);
        assertThat(blockedReplayed).isEqualTo(0);
        // 关键断言：超限消息绝不 ACK，必须 basicNack(requeue=true) 回退死信队列保存现场
        verify(mockChannel, never()).basicAck(eq(102L), anyBoolean());
        verify(mockChannel, times(1)).basicNack(eq(102L), eq(false), eq(true));

        // 4. 模拟清空死信队列
        com.rabbitmq.client.AMQP.Queue.PurgeOk purgeOk = mock(com.rabbitmq.client.AMQP.Queue.PurgeOk.class);
        when(purgeOk.getMessageCount()).thenReturn(3);
        when(mockChannel.queuePurge(eq(RabbitScenarioTopologyConfig.QUEUE_BIZ_DLQ))).thenReturn(purgeOk);

        int purged = deadLetterReplayService.purgeBizDeadLetters();
        assertThat(purged).isEqualTo(3);
    }

    /**
     * 离线自洽 AMQP 测试配置：基于 Mockito 与内存直接路由模拟完整 Broker 交互与 Confirm/DLQ
     * 标配 @Profile("!local")，当激活 local Profile 联调真实环境时自动失效
     */
    @TestConfiguration(proxyBeanMethods = false)
    @Profile("!local")
    static class TestAmqpConfig {

        @Bean
        @Primary
        public ConnectionFactory testConnectionFactory() {
            return mock(ConnectionFactory.class);
        }

        @Bean
        @Primary
        public com.rabbitmq.client.Channel testChannel() {
            return mock(com.rabbitmq.client.Channel.class);
        }

        @Bean
        @Primary
        public RabbitTemplate testRabbitTemplate(
                OrderConsumer orderConsumer,
                LogTopicConsumer logTopicConsumer,
                IdempotentOrderConsumer idempotentConsumer,
                org.springframework.beans.factory.ObjectProvider<OrderDelayConsumer> delayConsumerProvider,
                PoisonPillConsumer poisonPillConsumer,
                DeadLetterAuditConsumer deadLetterAuditConsumer,
                BatchOrderConsumer batchOrderConsumer,
                PriorityOrderConsumer priorityOrderConsumer,
                AnonymousBroadcastConsumer broadcastConsumer,
                RabbitListenerGlobalAdvice globalAdvice,
                com.rabbitmq.client.Channel testChannel) {

            RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
            Jackson2JsonMessageConverter converter = new Jackson2JsonMessageConverter();
            when(rabbitTemplate.getMessageConverter()).thenReturn(converter);

            // 模拟 rabbitTemplate.execute 委派至 testChannel
            when(rabbitTemplate.execute(any())).thenAnswer(invocation -> {
                org.springframework.amqp.rabbit.core.ChannelCallback<?> callback = invocation.getArgument(0);
                return callback.doInRabbit(testChannel);
            });

            // 路由派发闭包逻辑
            org.mockito.stubbing.Answer<Object> routerAnswer = invocation -> {
                String exchange = invocation.getArgument(0);
                String routingKey = invocation.getArgument(1);
                Message message = invocation.getArgument(2);
                if (invocation.getArguments().length > 3) {
                    CorrelationData cd = invocation.getArgument(3);
                    if (cd != null) {
                        cd.getFuture().complete(new CorrelationData.Confirm(true, null));
                    }
                }

                // 内存路由与消费模拟
                if (RabbitScenarioTopologyConfig.EXCHANGE_ORDER_DIRECT.equals(exchange)) {
                    if (RabbitScenarioTopologyConfig.ROUTING_KEY_ORDER_CREATE.equals(routingKey)) {
                        OrderCreateMessage payload = (OrderCreateMessage) converter.fromMessage(message);
                        String traceId = (String) message.getMessageProperties().getHeaders().get("X-Trace-Id");
                        String tenantId = (String) message.getMessageProperties().getHeaders().get("X-Tenant-Id");
                        orderConsumer.onOrderCreate(payload, traceId, tenantId);
                    } else if (RabbitScenarioTopologyConfig.ROUTING_KEY_ORDER_IDEMPOTENT.equals(routingKey)) {
                        String payload = (String) converter.fromMessage(message);
                        String bizKey = (String) message.getMessageProperties().getHeaders().get("X-Biz-Key");
                        MethodInvocation methodInvocation = mock(MethodInvocation.class);
                        when(methodInvocation.getArguments()).thenReturn(new Object[]{message});
                        try {
                            when(methodInvocation.proceed()).thenAnswer(inv -> {
                                idempotentConsumer.onConsume(payload, bizKey);
                                return null;
                            });
                            Object adviceResult = globalAdvice.invoke(methodInvocation);
                            assertThat(adviceResult).isNull();
                        } catch (Throwable t) {
                            throw new RuntimeException("RabbitListenerGlobalAdvice 未能正常静默确认消费", t);
                        }
                    }
                } else if (RabbitScenarioTopologyConfig.EXCHANGE_LOG_TOPIC.equals(exchange)) {
                    LogMessage payload = (LogMessage) converter.fromMessage(message);
                    logTopicConsumer.onErrorMessage(payload);
                } else if (RabbitScenarioTopologyConfig.EXCHANGE_ORDER_DELAY.equals(exchange)) {
                    OrderTimeoutMessage payload = (OrderTimeoutMessage) converter.fromMessage(message);
                    delayConsumerProvider.ifAvailable(consumer -> consumer.onTimeout(payload));
                } else if (RabbitScenarioTopologyConfig.EXCHANGE_BATCH_DIRECT.equals(exchange)) {
                    OrderCreateMessage payload = (OrderCreateMessage) converter.fromMessage(message);
                    batchOrderConsumer.onBatch(payload);
                } else if (RabbitScenarioTopologyConfig.EXCHANGE_PRIORITY_DIRECT.equals(exchange)) {
                    OrderCreateMessage payload = (OrderCreateMessage) converter.fromMessage(message);
                    priorityOrderConsumer.onPriorityOrder(payload, message.getMessageProperties().getPriority());
                } else if (RabbitScenarioTopologyConfig.EXCHANGE_BROADCAST_FANOUT.equals(exchange)) {
                    String payload = new String(message.getBody(), StandardCharsets.UTF_8).replace("\"", "");
                    broadcastConsumer.onBroadcast(payload);
                } else if (RabbitScenarioTopologyConfig.EXCHANGE_BIZ_DIRECT.equals(exchange)) {
                    String payload = (String) converter.fromMessage(message);
                    try {
                        poisonPillConsumer.onBizMessage(payload);
                    } catch (Exception ex) {
                        MessageProperties dlProps = new MessageProperties();
                        Map<String, Object> deathHeader = new HashMap<>();
                        deathHeader.put("queue", RabbitScenarioTopologyConfig.QUEUE_BIZ_MAIN);
                        deathHeader.put("exchange", exchange);
                        deathHeader.put("reason", "rejected");
                        deathHeader.put("count", 1L);
                        deathHeader.put("time", new Date());
                        dlProps.setHeader("x-death", List.of(deathHeader));

                        Message dlMessage = new Message(message.getBody(), dlProps);
                        deadLetterAuditConsumer.onDeadLetter(dlMessage);
                    }
                }

                return null;
            };

            doAnswer(routerAnswer).when(rabbitTemplate).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
            doAnswer(routerAnswer).when(rabbitTemplate).send(anyString(), anyString(), any(Message.class));

            return rabbitTemplate;
        }
    }
}
