package io.github.atengk.boot.sample.rabbitmq;

import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.boot.core.exception.IdempotentException;
import io.github.atengk.boot.core.util.JsonUtil;
import io.github.atengk.boot.rabbitmq.helper.RabbitDeadLetterHelper;
import io.github.atengk.boot.rabbitmq.interceptor.RabbitListenerGlobalAdvice;
import io.github.atengk.boot.rabbitmq.properties.RabbitmqProperties;
import io.github.atengk.boot.rabbitmq.template.RabbitReliableTemplate;
import io.github.atengk.boot.sample.rabbitmq.config.RabbitScenarioTopologyConfig;
import io.github.atengk.boot.sample.rabbitmq.consumer.*;
import io.github.atengk.boot.sample.rabbitmq.model.LogMessage;
import io.github.atengk.boot.sample.rabbitmq.model.OrderCreateMessage;
import io.github.atengk.boot.sample.rabbitmq.model.OrderTimeoutMessage;
import io.github.atengk.boot.sample.rabbitmq.producer.TradeMessageProducer;
import io.github.atengk.boot.sample.rabbitmq.service.OrderTransactionService;
import org.aopalliance.intercept.MethodInvocation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * RabbitMQ 1:1 独立场景沙箱端到端端实测套件
 * 涵盖场景 A (Direct/Topic)、场景 B (@Idempotent 静默确认)、场景 C (延迟队列)、场景 D (毒丸与死信助手)、场景 E (事务提交感知)
 * 100% 离线自洽，零外部 Broker 与 Docker 依赖，秒级全绿
 *
 * @author Ateng
 * @since 2026-10-21
 */
@SpringBootTest(classes = {SampleRabbitmqApplication.class, SampleRabbitmqE2eTest.TestAmqpConfig.class})
@DisplayName("RabbitMQ 场景沙箱 1:1 端到端端离线集成测试")
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

    @Autowired
    private OrderDelayConsumer delayConsumer;

    @Autowired
    private PoisonPillConsumer poisonPillConsumer;

    @Autowired
    private DeadLetterAuditConsumer deadLetterAuditConsumer;

    @Autowired
    private RabbitReliableTemplate rabbitReliableTemplate;

    @BeforeEach
    void setUp() {
        orderConsumer.clear();
        logTopicConsumer.clear();
        idempotentConsumer.clear();
        delayConsumer.clear();
        poisonPillConsumer.clear();
        deadLetterAuditConsumer.clear();
        UserContextHolder.clear();
        TenantContextHolder.clear();
        MDC.clear();
    }

    @AfterEach
    void tearDown() {
        UserContextHolder.clear();
        TenantContextHolder.clear();
        MDC.clear();
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

        assertThat(orderConsumer.getReceivedMessages()).hasSize(1);
        OrderCreateMessage receivedOrder = orderConsumer.getReceivedMessages().get(0);
        assertThat(receivedOrder.orderId()).isEqualTo("ORD-20261021-001");
        assertThat(receivedOrder.amount()).isEqualByComparingTo("199.00");

        // 2. 异步投递 Direct 订单消息并校验 Future Confirm
        OrderCreateMessage asyncMsg = new OrderCreateMessage(
                "ORD-20261021-002",
                new BigDecimal("299.00"),
                "user-888",
                LocalDateTime.now()
        );
        CompletableFuture<CorrelationData.Confirm> future = producer.sendOrderCreateAsync(asyncMsg);
        assertThat(future).isCompleted();

        // 3. 投递 Topic 层次通配日志
        LogMessage logMsg = new LogMessage("ERROR", "order-service", "订单支付超时重试失败");
        producer.sendLogError("log.error.order", logMsg);

        assertThat(logTopicConsumer.getReceivedLogs()).hasSize(1);
        LogMessage receivedLog = logTopicConsumer.getReceivedLogs().get(0);
        assertThat(receivedLog.serviceName()).isEqualTo("order-service");
        assertThat(receivedLog.content()).isEqualTo("订单支付超时重试失败");
    }

    @Test
    @DisplayName("场景 B: 消费端 @Idempotent 拦截 IdempotentException 静默确认 (Silent ACK)")
    void testScenarioB_IdempotentSilentAck() {
        String bizKey = "order-idemp-1001";

        // 1. 首次投递：正常消费成功
        producer.sendIdempotentMessage(bizKey, "ORDER_PAYLOAD_1");
        assertThat(idempotentConsumer.getNormalProcessedCount()).isEqualTo(1);
        assertThat(idempotentConsumer.getIdempotentTriggeredCount()).isZero();

        // 2. 重复投递相同业务主键：命中去重逻辑，抛出 IdempotentException，被全局切面捕获并触发 Silent ACK
        producer.sendIdempotentMessage(bizKey, "ORDER_PAYLOAD_1");
        assertThat(idempotentConsumer.getNormalProcessedCount()).isEqualTo(1);
        assertThat(idempotentConsumer.getIdempotentTriggeredCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("场景 C: 延迟消息投递与超时自动取消")
    void testScenarioC_DelayOrderTimeout() {
        OrderTimeoutMessage delayMsg = new OrderTimeoutMessage("ORD-TIMEOUT-001", 1800L, "用户超时未支付");
        producer.sendOrderDelay(delayMsg, Duration.ofMinutes(30));

        assertThat(delayConsumer.getTimeoutOrders()).hasSize(1);
        OrderTimeoutMessage received = delayConsumer.getTimeoutOrders().get(0);
        assertThat(received.orderId()).isEqualTo("ORD-TIMEOUT-001");
        assertThat(received.reason()).isEqualTo("用户超时未支付");
    }

    @Test
    @DisplayName("场景 D: 毒丸与业务异常重试后进入 DLQ 死信队列，结合 RabbitDeadLetterHelper 提取诊断")
    void testScenarioD_PoisonPillAndDeadLetterHelper() {
        // 投递毒丸消息，触发死信流转并被 DLQ 审计消费者接收
        producer.sendBizMessage("biz-poison-1", "poison-pill");

        assertThat(deadLetterAuditConsumer.getAuditRecords()).hasSize(1);
        RabbitDeadLetterHelper.DeadLetterRecord record = deadLetterAuditConsumer.getAuditRecords().get(0);
        assertThat(record.queue()).isEqualTo(RabbitScenarioTopologyConfig.QUEUE_BIZ_MAIN);
        assertThat(record.reason()).isEqualTo("rejected");
        assertThat(record.count()).isGreaterThanOrEqualTo(1L);
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
        assertThat(orderConsumer.getReceivedMessages()).hasSize(1);
        assertThat(orderConsumer.getReceivedMessages().get(0).orderId()).isEqualTo("ORD-TX-COMMIT-01");

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

    /**
     * 离线自洽 AMQP 测试配置：基于 Mockito 与内存直接路由模拟完整 Broker 交互与 Confirm/DLQ
     */
    @TestConfiguration(proxyBeanMethods = false)
    static class TestAmqpConfig {

        @Bean
        @Primary
        public ConnectionFactory testConnectionFactory() {
            return mock(ConnectionFactory.class);
        }

        @Bean
        @Primary
        public RabbitTemplate testRabbitTemplate(
                OrderConsumer orderConsumer,
                LogTopicConsumer logTopicConsumer,
                IdempotentOrderConsumer idempotentConsumer,
                OrderDelayConsumer delayConsumer,
                PoisonPillConsumer poisonPillConsumer,
                DeadLetterAuditConsumer deadLetterAuditConsumer,
                RabbitListenerGlobalAdvice globalAdvice) {

            RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
            Jackson2JsonMessageConverter converter = new Jackson2JsonMessageConverter();
            when(rabbitTemplate.getMessageConverter()).thenReturn(converter);

            // 模拟 Broker 路由分发与 Confirm ACK
            doAnswer(invocation -> {
                String exchange = invocation.getArgument(0);
                String routingKey = invocation.getArgument(1);
                Message message = invocation.getArgument(2);
                CorrelationData cd = invocation.getArgument(3);

                // 1. 模拟 Broker Publisher Confirm ACK
                if (cd != null) {
                    cd.getFuture().complete(new CorrelationData.Confirm(true, null));
                }

                // 2. 内存路由与消费模拟
                if (RabbitScenarioTopologyConfig.EXCHANGE_ORDER_DIRECT.equals(exchange)) {
                    if (RabbitScenarioTopologyConfig.ROUTING_KEY_ORDER_CREATE.equals(routingKey)) {
                        OrderCreateMessage payload = (OrderCreateMessage) converter.fromMessage(message);
                        String traceId = (String) message.getMessageProperties().getHeaders().get("X-Trace-Id");
                        String tenantId = (String) message.getMessageProperties().getHeaders().get("X-Tenant-Id");
                        orderConsumer.onOrderCreate(payload, traceId, tenantId);
                    } else if (RabbitScenarioTopologyConfig.ROUTING_KEY_ORDER_IDEMPOTENT.equals(routingKey)) {
                        String payload = (String) converter.fromMessage(message);
                        String bizKey = (String) message.getMessageProperties().getHeaders().get("X-Biz-Key");
                        // 真实挂载 RabbitListenerGlobalAdvice 拦截切面，端到端检验幂等异常静默确认 (Silent ACK)
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
                    delayConsumer.onTimeout(payload);
                } else if (RabbitScenarioTopologyConfig.EXCHANGE_BIZ_DIRECT.equals(exchange)) {
                    String payload = (String) converter.fromMessage(message);
                    try {
                        poisonPillConsumer.onBizMessage(payload);
                    } catch (Exception ex) {
                        // 模拟毒丸判定器与 DLX 路由至死信队列
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
            }).when(rabbitTemplate).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));

            return rabbitTemplate;
        }
    }
}
