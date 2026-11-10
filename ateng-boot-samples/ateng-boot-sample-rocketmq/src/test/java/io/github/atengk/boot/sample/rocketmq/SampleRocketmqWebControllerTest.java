package io.github.atengk.boot.sample.rocketmq;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.atengk.boot.core.validation.ValidList;
import io.github.atengk.boot.sample.rocketmq.dto.*;
import io.github.atengk.boot.sample.rocketmq.model.OrderMessage;
import io.github.atengk.boot.sample.rocketmq.producer.TradeRocketmqProducer;
import io.github.atengk.boot.sample.rocketmq.testdouble.InMemoryRocketmqBroker;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.apis.message.MessageView;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * RocketMQ Sample 场景控制器端到端 MockMvc 集成测试
 *
 * <p>全量覆盖领域 Controller 的 HTTP 路由、JSR-303 校验拦截与 Result 契约。</p>
 *
 * @author Ateng
 * @since 2026-11-10
 */
@Slf4j
@SpringBootTest(classes = SampleRocketmqApplication.class)
@AutoConfigureMockMvc
@Import(SampleRocketmqE2eTest.TestRocketmqConfig.class)
@DisplayName("RocketMQ Sample Web 控制器全场景 MockMvc 测试")
class SampleRocketmqWebControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private TradeRocketmqProducer tradeRocketmqProducer;

    @Autowired(required = false)
    private InMemoryRocketmqBroker testBroker;

    @Test
    @DisplayName("测试 RocketmqProduceController: 同步/异步/单向/FIFO/SQL92/批量与审计端点")
    void testProduceController() throws Exception {
        // 1. 同步投递合法订单
        OrderProduceRequest syncReq = OrderProduceRequest.builder()
                .orderId("ORD-WEB-SYNC-001")
                .amount(188.0)
                .customerName("张三")
                .tag("create")
                .build();

        mockMvc.perform(post("/sample/rocketmq/produce/sync")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(syncReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.orderId").value("ORD-WEB-SYNC-001"))
                .andExpect(jsonPath("$.data.messageId").isNotEmpty())
                .andExpect(jsonPath("$.data.status").value("SUCCESS"));

        // 2. JSR-303 参数缺失 Fail-Fast 校验拦截 (orderId 为空)
        OrderProduceRequest invalidReq = OrderProduceRequest.builder()
                .amount(100.0)
                .build();

        mockMvc.perform(post("/sample/rocketmq/produce/sync")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalidReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(not(200)))
                .andExpect(jsonPath("$.success").value(false));

        // 3. JSR-303 参数格式错误 Fail-Fast 校验拦截 (金额为负数)
        OrderProduceRequest negativeAmountReq = OrderProduceRequest.builder()
                .orderId("ORD-WEB-NEG-001")
                .amount(-50.0)
                .build();

        mockMvc.perform(post("/sample/rocketmq/produce/sync")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(negativeAmountReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(not(200)))
                .andExpect(jsonPath("$.success").value(false));

        // 4. 异步投递订单
        OrderProduceRequest asyncReq = OrderProduceRequest.builder()
                .orderId("ORD-WEB-ASYNC-001")
                .amount(299.0)
                .customerName("李四")
                .build();

        mockMvc.perform(post("/sample/rocketmq/produce/async")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(asyncReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.orderId").value("ORD-WEB-ASYNC-001"))
                .andExpect(jsonPath("$.data.status").value("ASYNC_SUCCESS"));

        // 5. 单向发送埋点消息
        OrderProduceRequest onewayReq = OrderProduceRequest.builder()
                .orderId("ORD-WEB-ONEWAY-001")
                .amount(10.0)
                .customerName("王五")
                .build();

        mockMvc.perform(post("/sample/rocketmq/produce/oneway")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(onewayReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        // 6. FIFO 顺序保序投递
        OrderProduceRequest fifoReq = OrderProduceRequest.builder()
                .orderId("ORD-WEB-FIFO-001")
                .amount(500.0)
                .messageGroup("GROUP_ORDER_1001")
                .build();

        mockMvc.perform(post("/sample/rocketmq/produce/fifo")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(fifoReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.orderId").value("ORD-WEB-FIFO-001"))
                .andExpect(jsonPath("$.data.status").value("FIFO_SUCCESS"));

        // 7. SQL92 服务端属性过滤投递
        OrderProduceRequest sqlReq = OrderProduceRequest.builder()
                .orderId("ORD-WEB-SQL-001")
                .amount(250.0)
                .build();

        mockMvc.perform(post("/sample/rocketmq/produce/sql")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(sqlReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.orderId").value("ORD-WEB-SQL-001"))
                .andExpect(jsonPath("$.data.status").value("SQL_SUCCESS"));

        // 8. 批量投递 (ValidList 正常场景)
        ValidList<OrderProduceRequest> batchReq = new ValidList<>(List.of(
                OrderProduceRequest.builder().orderId("ORD-BATCH-001").amount(66.0).build(),
                OrderProduceRequest.builder().orderId("ORD-BATCH-002").amount(88.0).build()
        ));

        mockMvc.perform(post("/sample/rocketmq/produce/batch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(batchReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.total").value(2))
                .andExpect(jsonPath("$.data.successCount").value(2))
                .andExpect(jsonPath("$.data.orderIds", hasSize(2)));

        // 9. 批量投递 (ValidList 元素校验失败场景)
        ValidList<OrderProduceRequest> invalidBatchReq = new ValidList<>(List.of(
                OrderProduceRequest.builder().orderId("ORD-BATCH-003").amount(66.0).build(),
                OrderProduceRequest.builder().orderId("").amount(88.0).build() // 空 ID 触发校验拦截
        ));

        mockMvc.perform(post("/sample/rocketmq/produce/batch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalidBatchReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(not(200)))
                .andExpect(jsonPath("$.success").value(false));

        // 10. 消费审计端点查询
        mockMvc.perform(get("/sample/rocketmq/produce/received"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").isArray());

        // 11. 清空审计记录端点
        mockMvc.perform(delete("/sample/rocketmq/produce/clear"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    @DisplayName("测试 RocketmqDelayController: 相对延时/绝对定时/精准撤回与消费审计")
    void testDelayController() throws Exception {
        // 1. 相对时长延时投递订单
        OrderDelayRequest delayReq = OrderDelayRequest.builder()
                .orderId("ORD-WEB-DELAY-001")
                .amount(88.0)
                .delaySeconds(60L)
                .build();

        String delayResponseStr = mockMvc.perform(post("/sample/rocketmq/delay/duration")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(delayReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.orderId").value("ORD-WEB-DELAY-001"))
                .andExpect(jsonPath("$.data.recallHandle").isNotEmpty())
                .andExpect(jsonPath("$.data.status").value("DELAY_SCHEDULED"))
                .andReturn().getResponse().getContentAsString();

        // 提取返回的 recallHandle
        String recallHandle = objectMapper.readTree(delayResponseStr).path("data").path("recallHandle").asText();

        // 2. 校验 JSR-303 参数缺失拦截 (orderId 为空)
        OrderDelayRequest invalidDelayReq = OrderDelayRequest.builder()
                .amount(50.0)
                .build();

        mockMvc.perform(post("/sample/rocketmq/delay/duration")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalidDelayReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(not(200)))
                .andExpect(jsonPath("$.success").value(false));

        // 3. 绝对未来时间戳定时投递
        long futureTimestamp = System.currentTimeMillis() + 3600_000L;
        OrderDelayRequest deliverAtReq = OrderDelayRequest.builder()
                .orderId("ORD-WEB-DELIVER-001")
                .amount(168.0)
                .deliverAtTimestamp(futureTimestamp)
                .build();

        mockMvc.perform(post("/sample/rocketmq/delay/deliver-at")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(deliverAtReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.orderId").value("ORD-WEB-DELIVER-001"))
                .andExpect(jsonPath("$.data.deliverAtTimestamp").value(futureTimestamp))
                .andExpect(jsonPath("$.data.status").value("SCHEDULED_SUCCESS"));

        // 4. 精准撤回延时消息
        OrderRecallRequest recallReq = OrderRecallRequest.builder()
                .topic("sample_delay_topic")
                .recallHandle(recallHandle)
                .build();

        mockMvc.perform(post("/sample/rocketmq/delay/recall")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(recallReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value(true));

        // 5. 校验 recallHandle 为空时 JSR-303 拦截
        OrderRecallRequest invalidRecallReq = OrderRecallRequest.builder()
                .recallHandle("")
                .build();

        mockMvc.perform(post("/sample/rocketmq/delay/recall")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalidRecallReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(not(200)))
                .andExpect(jsonPath("$.success").value(false));

        // 6. 查询延时消费审计记录
        mockMvc.perform(get("/sample/rocketmq/delay/received"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").isArray());

        // 7. 清空延时消费审计记录
        mockMvc.perform(delete("/sample/rocketmq/delay/clear"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    @DisplayName("测试 RocketmqTransactionController: 2PC半消息/sendAfterCommit/回查审计")
    void testTransactionController() throws Exception {
        // 1. 发送 2PC 事务半消息 (本地决断 COMMIT)
        OrderTransactionRequest commitReq = OrderTransactionRequest.builder()
                .orderId("ORD-WEB-TX-COMMIT-001")
                .amount(666.0)
                .localSuccess(true)
                .build();

        mockMvc.perform(post("/sample/rocketmq/transaction/half-message")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(commitReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.orderId").value("ORD-WEB-TX-COMMIT-001"))
                .andExpect(jsonPath("$.data.status").value("2PC_COMMITTED"));

        // 2. 发送 2PC 事务半消息 (本地决断 ROLLBACK)
        OrderTransactionRequest rollbackReq = OrderTransactionRequest.builder()
                .orderId("ORD-WEB-TX-ROLLBACK-001")
                .amount(111.0)
                .localSuccess(false)
                .build();

        mockMvc.perform(post("/sample/rocketmq/transaction/half-message")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(rollbackReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.orderId").value("ORD-WEB-TX-ROLLBACK-001"))
                .andExpect(jsonPath("$.data.status").value("2PC_ROLLED_BACK"));

        // 3. JSR-303 参数缺失拦截 (orderId 为空)
        OrderTransactionRequest invalidTxReq = OrderTransactionRequest.builder()
                .amount(100.0)
                .build();

        mockMvc.perform(post("/sample/rocketmq/transaction/half-message")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalidTxReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(not(200)))
                .andExpect(jsonPath("$.success").value(false));

        // 4. sendAfterCommit 本地事务成功提交后发信
        OrderTransactionRequest postCommitReq = OrderTransactionRequest.builder()
                .orderId("ORD-WEB-POST-COMMIT-001")
                .amount(333.0)
                .localSuccess(true)
                .build();

        mockMvc.perform(post("/sample/rocketmq/transaction/send-after-commit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(postCommitReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value("TX_COMMITTED_AND_SENT"));

        // 5. sendAfterCommit 本地事务回滚零发信
        OrderTransactionRequest postRollbackReq = OrderTransactionRequest.builder()
                .orderId("ORD-WEB-POST-ROLLBACK-001")
                .amount(444.0)
                .localSuccess(false)
                .build();

        mockMvc.perform(post("/sample/rocketmq/transaction/send-after-commit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(postRollbackReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value("TX_ROLLED_BACK_ZERO_SEND"));

        // 6. 查询回查器状态
        mockMvc.perform(get("/sample/rocketmq/transaction/checker/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.committedOrders").isArray())
                .andExpect(jsonPath("$.data.rolledBackOrders").isArray())
                .andExpect(jsonPath("$.data.localDbOrders").isArray());

        // 7. 查询事务消费者已接收消费的订单列表
        mockMvc.perform(get("/sample/rocketmq/transaction/received"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").isArray());

        // 8. 清空事务审计记录
        mockMvc.perform(delete("/sample/rocketmq/transaction/clear"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    @DisplayName("测试 RocketmqSimpleConsumerController: 流式微批拉取/租约续期/显式ACK/闭包消费与句柄审计")
    void testSimpleConsumerController() throws Exception {
        // 0. 清理历史缓存
        mockMvc.perform(delete("/sample/rocketmq/simple-consumer/clear"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        // 1. 发送 2 条待拉取订单消息
        OrderMessage order1 = OrderMessage.builder().orderId("ORD-WEB-SIMPLE-001").amount(100.0).customerName("拉取买家1").status("CREATED").build();
        OrderMessage order2 = OrderMessage.builder().orderId("ORD-WEB-SIMPLE-002").amount(200.0).customerName("拉取买家2").status("CREATED").build();
        tradeRocketmqProducer.sendOrderWithTag(order1, "simple_pull");
        tradeRocketmqProducer.sendOrderWithTag(order2, "simple_pull");

        // 2. 微批长轮询流式拉取消息
        SimplePullRequest pullReq = SimplePullRequest.builder()
                .tag("simple_pull")
                .maxNum(10)
                .invisibleSeconds(30L)
                .build();

        String pullResultStr = mockMvc.perform(post("/sample/rocketmq/simple-consumer/pull")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(pullReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data", hasSize(greaterThanOrEqualTo(1))))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        JsonNode rootNode = objectMapper.readTree(pullResultStr);
        String messageId = rootNode.path("data").get(0).path("messageId").asText();
        assertThat(messageId).isNotBlank();

        // 3. 审计待决租约句柄
        mockMvc.perform(get("/sample/rocketmq/simple-consumer/cached-views"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.cachedCount", greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$.data.messages", hasSize(greaterThanOrEqualTo(1))));

        // 4. 动态续期不可见租约
        SimpleRenewRequest renewReq = SimpleRenewRequest.builder()
                .messageId(messageId)
                .invisibleSeconds(60L)
                .build();

        mockMvc.perform(post("/sample/rocketmq/simple-consumer/renew")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(renewReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data", containsString("租约续期成功")));

        // 5. 租约续期 JSR-303 参数缺失拦截 (messageId 为空)
        SimpleRenewRequest invalidRenewReq = SimpleRenewRequest.builder()
                .invisibleSeconds(60L)
                .build();

        mockMvc.perform(post("/sample/rocketmq/simple-consumer/renew")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalidRenewReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(not(200)))
                .andExpect(jsonPath("$.success").value(false));

        // 6. 显式确认出队 (ACK)
        SimpleAckRequest ackReq = SimpleAckRequest.builder()
                .messageId(messageId)
                .build();

        mockMvc.perform(post("/sample/rocketmq/simple-consumer/ack")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(ackReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data", containsString("消息确认出队成功")));

        // 7. 对已确认出队的消息再次确认，触发防御拦截
        mockMvc.perform(post("/sample/rocketmq/simple-consumer/ack")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(ackReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(not(200)))
                .andExpect(jsonPath("$.success").value(false));

        // 8. 高阶函数式闭包一步消费 (receiveAndExecute)
        OrderMessage closureOrder = OrderMessage.builder().orderId("ORD-WEB-CLOSURE-001").amount(99.0).customerName("闭包买家").status("CREATED").build();
        tradeRocketmqProducer.sendOrderWithTag(closureOrder, "simple_pull");

        SimplePullRequest closureReq = SimplePullRequest.builder()
                .tag("simple_pull")
                .maxNum(5)
                .invisibleSeconds(30L)
                .build();

        mockMvc.perform(post("/sample/rocketmq/simple-consumer/consume")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(closureReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data", greaterThanOrEqualTo(1)));

        // 9. 查询闭包消费接收记录
        mockMvc.perform(get("/sample/rocketmq/simple-consumer/consumed"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data", hasSize(greaterThanOrEqualTo(1))))
                .andExpect(jsonPath("$.data[?(@.orderId == 'ORD-WEB-CLOSURE-001')]").exists());

        // 10. 清空句柄缓存与消费记录
        mockMvc.perform(delete("/sample/rocketmq/simple-consumer/clear"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        mockMvc.perform(get("/sample/rocketmq/simple-consumer/cached-views"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.cachedCount").value(0))
                .andExpect(jsonPath("$.data.messages", empty()));
    }

    @Test
    @DisplayName("测试 RocketmqContainerController: 端点审计/动态削峰暂停/恢复/物理启停与全局调度")
    void testContainerController() throws Exception {
        // 1. 端点元数据列表审计
        mockMvc.perform(get("/sample/rocketmq/container/endpoints"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data", hasSize(greaterThanOrEqualTo(1))))
                .andExpect(jsonPath("$.data[?(@.endpointId == 'sample_order_group#sample_order_topic')]").exists());

        // 2. 针对指定端点动态暂停削峰
        ContainerControlRequest pauseReq = ContainerControlRequest.builder()
                .endpointId("sample_order_group#sample_order_topic")
                .build();

        mockMvc.perform(post("/sample/rocketmq/container/pause")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(pauseReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value(true));

        // 验证端点状态为 paused=true
        mockMvc.perform(get("/sample/rocketmq/container/endpoints"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.endpointId == 'sample_order_group#sample_order_topic')].paused").value(true));

        // 3. 恢复端点正常消费
        ContainerControlRequest resumeReq = ContainerControlRequest.builder()
                .endpointId("sample_order_group#sample_order_topic")
                .build();

        mockMvc.perform(post("/sample/rocketmq/container/resume")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(resumeReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value(true));

        // 验证端点状态为 paused=false
        mockMvc.perform(get("/sample/rocketmq/container/endpoints"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.endpointId == 'sample_order_group#sample_order_topic')].paused").value(false));

        // 4. 物理关闭底层连接 (stop)
        ContainerControlRequest stopReq = ContainerControlRequest.builder()
                .endpointId("sample_order_group#sample_order_topic")
                .build();

        mockMvc.perform(post("/sample/rocketmq/container/stop")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(stopReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value(true));

        // 5. 重新组装启动底层连接 (start)
        ContainerControlRequest startReq = ContainerControlRequest.builder()
                .endpointId("sample_order_group#sample_order_topic")
                .build();

        mockMvc.perform(post("/sample/rocketmq/container/start")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(startReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value(true));

        // 6. JSR-303 参数校验拦截 (endpointId 为空)
        ContainerControlRequest invalidReq = ContainerControlRequest.builder().build();

        mockMvc.perform(post("/sample/rocketmq/container/pause")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalidReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(not(200)))
                .andExpect(jsonPath("$.success").value(false));

        // 7. 全局暂停与全局恢复
        mockMvc.perform(post("/sample/rocketmq/container/pause-all"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data", greaterThanOrEqualTo(1)));

        mockMvc.perform(post("/sample/rocketmq/container/resume-all"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data", greaterThanOrEqualTo(1)));
    }

    @Test
    @DisplayName("测试 RocketmqDeadLetterController: 死信只读探查/受控重放至业务主题/目标分流/批量丢弃")
    void testDeadLetterController() throws Exception {
        String dlqGroup = "sample_order_group";
        String dlqTopic = "%DLQ%" + dlqGroup;

        // 1. 模拟死信报文入队
        Map<String, String> peekProps = Map.of(
                "X-Original-Topic", TradeRocketmqProducer.TOPIC_ORDER,
                "X-Biz-Key", "ORD-DLQ-WEB-PEEK"
        );
        if (testBroker != null) {
            MessageView peekMsg = InMemoryRocketmqBroker.createMockMessageView(
                    dlqTopic, "DLQ-WEB-PEEK-001", "{\"orderId\":\"ORD-DLQ-WEB-PEEK\"}".getBytes(StandardCharsets.UTF_8),
                    peekProps, "create", null
            );
            testBroker.enqueueDlqMessage(dlqGroup, peekMsg);
        }

        // 2. 只读探查死信 (零 ACK 保留现场)
        DeadLetterPeekRequest peekReq = DeadLetterPeekRequest.builder()
                .consumerGroup(dlqGroup)
                .limit(10)
                .build();

        mockMvc.perform(post("/sample/rocketmq/dead-letter/peek")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(peekReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data", hasSize(greaterThanOrEqualTo(1))))
                .andExpect(jsonPath("$.data[?(@.messageId == 'DLQ-WEB-PEEK-001')]").exists());

        if (testBroker != null) {
            assertThat(testBroker.getAckedMessageIds()).doesNotContain("DLQ-WEB-PEEK-001");
        }

        // 3. JSR-303 参数缺失拦截 (consumerGroup 为空)
        DeadLetterPeekRequest invalidPeekReq = DeadLetterPeekRequest.builder().build();

        mockMvc.perform(post("/sample/rocketmq/dead-letter/peek")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalidPeekReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(not(200)))
                .andExpect(jsonPath("$.success").value(false));

        // 4. 受控重放死信至原始主题并确认 ACK
        Map<String, String> replayProps = Map.of(
                "X-Original-Topic", TradeRocketmqProducer.TOPIC_ORDER,
                "X-Biz-Key", "ORD-DLQ-WEB-REPLAY"
        );
        if (testBroker != null) {
            MessageView replayMsg = InMemoryRocketmqBroker.createMockMessageView(
                    dlqTopic, "DLQ-WEB-REPLAY-001", "{\"orderId\":\"ORD-DLQ-WEB-REPLAY\"}".getBytes(StandardCharsets.UTF_8),
                    replayProps, "create", null
            );
            testBroker.enqueueDlqMessage(dlqGroup, replayMsg);
        }

        DeadLetterReplayRequest replayReq = DeadLetterReplayRequest.builder()
                .consumerGroup(dlqGroup)
                .limit(10)
                .build();

        mockMvc.perform(post("/sample/rocketmq/dead-letter/replay")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(replayReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.successCount", greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$.data.details", hasSize(greaterThanOrEqualTo(1))));

        if (testBroker != null) {
            assertThat(testBroker.getAckedMessageIds()).contains("DLQ-WEB-REPLAY-001");
        }

        // 5. 受控重放死信至指定分流主题
        Map<String, String> topicDlqProps = Map.of(
                "X-Original-Topic", TradeRocketmqProducer.TOPIC_ORDER,
                "X-Biz-Key", "ORD-DLQ-WEB-TOPIC"
        );
        if (testBroker != null) {
            MessageView topicMsg = InMemoryRocketmqBroker.createMockMessageView(
                    dlqTopic, "DLQ-WEB-TOPIC-001", "{\"orderId\":\"ORD-DLQ-WEB-TOPIC\"}".getBytes(StandardCharsets.UTF_8),
                    topicDlqProps, "create", null
            );
            testBroker.enqueueDlqMessage(dlqGroup, topicMsg);
        }

        DeadLetterReplayRequest targetTopicReq = DeadLetterReplayRequest.builder()
                .consumerGroup(dlqGroup)
                .limit(10)
                .targetTopic(TradeRocketmqProducer.TOPIC_ORDER)
                .build();

        mockMvc.perform(post("/sample/rocketmq/dead-letter/replay")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(targetTopicReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.successCount", greaterThanOrEqualTo(1)));

        // 6. 批量物理丢弃清理死信
        Map<String, String> discardProps = Map.of(
                "X-Original-Topic", TradeRocketmqProducer.TOPIC_ORDER,
                "X-Biz-Key", "ORD-DLQ-WEB-DISCARD"
        );
        if (testBroker != null) {
            MessageView discardMsg = InMemoryRocketmqBroker.createMockMessageView(
                    dlqTopic, "DLQ-WEB-DISCARD-001", "{\"orderId\":\"ORD-DLQ-WEB-DISCARD\"}".getBytes(StandardCharsets.UTF_8),
                    discardProps, "create", null
            );
            testBroker.enqueueDlqMessage(dlqGroup, discardMsg);
        }

        DeadLetterDiscardRequest discardReq = DeadLetterDiscardRequest.builder()
                .consumerGroup(dlqGroup)
                .limit(10)
                .build();

        mockMvc.perform(post("/sample/rocketmq/dead-letter/discard")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(discardReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data", greaterThanOrEqualTo(1)));

        if (testBroker != null) {
            assertThat(testBroker.getAckedMessageIds()).contains("DLQ-WEB-DISCARD-001");
        }
    }
}
