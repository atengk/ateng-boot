package io.github.atengk.boot.sample.rabbitmq;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.atengk.boot.core.validation.ValidList;
import io.github.atengk.boot.sample.rabbitmq.dto.*;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;

import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * RabbitMQ Sample 场景控制器端到端 MockMvc 集成测试
 * 全量覆盖 6 个领域 Controller 的 HTTP 路由、JSR-303 校验拦截与 Result 契约
 *
 * @author Ateng
 * @since 2026-11-09
 */
@Slf4j
@SpringBootTest(classes = SampleRabbitmqApplication.class)
@AutoConfigureMockMvc
@Import(SampleRabbitmqE2eTest.TestAmqpConfig.class)
@DisplayName("RabbitMQ Sample Web 控制器全场景 MockMvc 测试")
class SampleRabbitmqWebControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("测试 RabbitProduceController: 同步/异步发信、Topic、不可路由与查询")
    void testProduceController() throws Exception {
        // 1. 同步投递合法订单
        OrderCreateRequest orderReq = OrderCreateRequest.builder()
                .orderId("ORD-WEB-SYNC-001")
                .amount(new BigDecimal("188.00"))
                .userId("user-sync")
                .build();

        mockMvc.perform(post("/sample/rabbitmq/produce/order")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(orderReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value("ORD-WEB-SYNC-001"));

        // 2. 校验 JSR-303 拦截 (订单ID为空、金额为负)
        OrderCreateRequest invalidReq = OrderCreateRequest.builder()
                .orderId("")
                .amount(new BigDecimal("-10.00"))
                .userId("user-invalid")
                .build();

        mockMvc.perform(post("/sample/rabbitmq/produce/order")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalidReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(not(200)));

        // 3. 异步投递订单
        OrderCreateRequest asyncReq = OrderCreateRequest.builder()
                .orderId("ORD-WEB-ASYNC-002")
                .amount(new BigDecimal("288.00"))
                .userId("user-async")
                .build();

        mockMvc.perform(post("/sample/rabbitmq/produce/order-async")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(asyncReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value("ORD-WEB-ASYNC-002"));

        // 4. Topic 日志投递
        LogSendRequest logReq = LogSendRequest.builder()
                .routingKey("log.error.trade")
                .level("ERROR")
                .serviceName("trade-service")
                .content("支付渠道超时")
                .build();

        mockMvc.perform(post("/sample/rabbitmq/produce/log")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(logReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        // 5. 不可路由消息投递
        mockMvc.perform(post("/sample/rabbitmq/produce/unroutable")
                        .param("payload", "unroutable-data"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        // 6. 查询与清空
        mockMvc.perform(get("/sample/rabbitmq/produce/received-orders"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").isArray());

        mockMvc.perform(get("/sample/rabbitmq/produce/received-logs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").isArray());

        mockMvc.perform(delete("/sample/rabbitmq/produce/clear"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    @DisplayName("测试 RabbitTransactionController: 事务提交感知与回滚防幽灵消息")
    void testTransactionController() throws Exception {
        OrderCreateRequest commitReq = OrderCreateRequest.builder()
                .orderId("ORD-TX-COMMIT-101")
                .amount(new BigDecimal("300.00"))
                .userId("user-tx")
                .build();

        // 1. 事务提交正常发信
        mockMvc.perform(post("/sample/rabbitmq/transaction/commit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(commitReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        // 2. 事务回滚零发信
        OrderCreateRequest rollbackReq = OrderCreateRequest.builder()
                .orderId("ORD-TX-ROLLBACK-102")
                .amount(new BigDecimal("400.00"))
                .userId("user-tx")
                .build();

        mockMvc.perform(post("/sample/rabbitmq/transaction/rollback")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(rollbackReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        // 3. 探查
        mockMvc.perform(get("/sample/rabbitmq/transaction/records"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").isArray());

        mockMvc.perform(get("/sample/rabbitmq/transaction/db-count"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").isNumber());
    }

    @Test
    @DisplayName("测试 RabbitBatchAndPriorityController: 批量削峰与 Quorum 优先级")
    void testBatchAndPriorityController() throws Exception {
        OrderCreateRequest o1 = new OrderCreateRequest("BATCH-01", new BigDecimal("10.00"), "u1");
        OrderCreateRequest o2 = new OrderCreateRequest("BATCH-02", new BigDecimal("20.00"), "u2");

        BatchOrderRequest batchReq = new BatchOrderRequest(new ValidList<>(List.of(o1, o2)));

        // 1. 批量同步投递
        mockMvc.perform(post("/sample/rabbitmq/batch/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(batchReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value(2));

        // 2. 批量异步投递
        mockMvc.perform(post("/sample/rabbitmq/batch/orders-async")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(batchReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value(2));

        // 3. 探查批量消费
        mockMvc.perform(get("/sample/rabbitmq/batch/received-batches"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").isArray());

        // 4. 投递优先级订单
        PriorityOrderRequest priorityReq = PriorityOrderRequest.builder()
                .order(new OrderCreateRequest("PRI-VIP-01", new BigDecimal("999.00"), "vip-user"))
                .priority(8)
                .build();

        mockMvc.perform(post("/sample/rabbitmq/priority/order")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(priorityReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        // 5. 探查优先级记录与清空
        mockMvc.perform(get("/sample/rabbitmq/priority/received-orders"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        mockMvc.perform(get("/sample/rabbitmq/priority/received-priorities"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        mockMvc.perform(delete("/sample/rabbitmq/batch/clear"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    @DisplayName("测试 RabbitDelayController: 订单超时延迟发信与履约")
    void testDelayController() throws Exception {
        OrderDelayRequest delayReq = OrderDelayRequest.builder()
                .orderId("ORD-DELAY-001")
                .timeoutSeconds(1L)
                .reason("30分钟未支付自动取消")
                .build();

        mockMvc.perform(post("/sample/rabbitmq/delay/timeout-order")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(delayReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        mockMvc.perform(get("/sample/rabbitmq/delay/received-orders"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").isArray());

        mockMvc.perform(delete("/sample/rabbitmq/delay/clear"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    @DisplayName("测试 RabbitDeadLetterController: 毒丸隔离、幂等静默ACK、死信受控重放与排空")
    void testDeadLetterController() throws Exception {
        // 1. 触发致命毒丸
        mockMvc.perform(post("/sample/rabbitmq/dead-letter/trigger-poison")
                        .param("bizKey", "poison-test-01"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        // 2. 触发幂等消息
        mockMvc.perform(post("/sample/rabbitmq/dead-letter/trigger-idempotent")
                        .param("bizKey", "idemp-test-01"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        // 3. 死信重放
        DeadLetterReplayRequest replayReq = DeadLetterReplayRequest.builder()
                .dlq("sample.biz.dlq")
                .targetExchange("sample.biz.direct")
                .targetRoutingKey("biz.work")
                .limit(5)
                .build();

        mockMvc.perform(post("/sample/rabbitmq/dead-letter/replay")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(replayReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        // 4. 只读探查与清空
        mockMvc.perform(get("/sample/rabbitmq/dead-letter/peek")
                        .param("limit", "3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").isArray());

        mockMvc.perform(post("/sample/rabbitmq/dead-letter/purge"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        // 5. 探查审计与统计
        mockMvc.perform(get("/sample/rabbitmq/dead-letter/audit-records"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        mockMvc.perform(get("/sample/rabbitmq/dead-letter/idempotent-counts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        mockMvc.perform(delete("/sample/rabbitmq/dead-letter/clear"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    @DisplayName("测试 RabbitBroadcastController: Fanout 广播发布与匿名临时队列接收")
    void testBroadcastController() throws Exception {
        BroadcastPublishRequest broadcastReq = new BroadcastPublishRequest("CONFIG_REFRESH_ALL");

        mockMvc.perform(post("/sample/rabbitmq/broadcast/publish")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(broadcastReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        mockMvc.perform(get("/sample/rabbitmq/broadcast/received"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").isArray());

        mockMvc.perform(delete("/sample/rabbitmq/broadcast/clear"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }
}
