package io.github.atengk.boot.sample.rocketmq;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.atengk.boot.core.validation.ValidList;
import io.github.atengk.boot.sample.rocketmq.dto.OrderProduceRequest;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

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
}
