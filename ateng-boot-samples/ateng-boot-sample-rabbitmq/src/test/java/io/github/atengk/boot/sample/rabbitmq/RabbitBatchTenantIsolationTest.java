package io.github.atengk.boot.sample.rabbitmq;

import io.github.atengk.boot.core.constant.MessageHeaderConstants;
import io.github.atengk.boot.core.context.MessageContextPropagator;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.boot.sample.rabbitmq.consumer.BatchOrderConsumer;
import io.github.atengk.boot.sample.rabbitmq.consumer.BatchTenantIsolationConsumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * RabbitBatchExecutor 批量多租户隔离与异常容错端到端集成测试
 *
 * @author Ateng
 * @since 2026-11-15
 */
@SpringBootTest(classes = SampleRabbitmqApplication.class)
@AutoConfigureMockMvc
@DisplayName("RabbitBatchExecutor 批量多租户隔离与容错端到端测试")
class RabbitBatchTenantIsolationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private BatchTenantIsolationConsumer batchTenantIsolationConsumer;

    @Autowired
    private BatchOrderConsumer batchOrderConsumer;

    @AfterEach
    void cleanUp() {
        batchTenantIsolationConsumer.clear();
        batchOrderConsumer.clear();
        UserContextHolder.clear();
        TenantContextHolder.clear();
        MDC.clear();
    }

    @Test
    @DisplayName("HTTP 批量多租户混排投递：无租户标签不串味、幂等重复静默跳过、单条异常不阻断剩余消息")
    void shouldIsolateTenantsAndSkipDuplicatesViaHttpEndpoints() throws Exception {
        String requestJson = """
                {
                  "items": [
                    {"orderId": "ORD-OK-1", "amount": 100.00, "tenantId": "tenant-alpha", "userId": "user-alpha", "ignoreTenant": false},
                    {"orderId": "ORD-OK-NONE", "amount": 50.00, "tenantId": null, "userId": null, "ignoreTenant": false},
                    {"orderId": "DUP-ORD-1", "amount": 10.00, "tenantId": "tenant-dup", "userId": "user-dup", "ignoreTenant": false},
                    {"orderId": "FAIL-ORD-1", "amount": 20.00, "tenantId": "tenant-fail", "userId": "user-fail", "ignoreTenant": false},
                    {"orderId": "ORD-OK-2", "amount": 200.00, "tenantId": "tenant-beta", "userId": "user-beta", "ignoreTenant": true}
                  ]
                }
                """;

        mockMvc.perform(post("/sample/rabbitmq/batch/tenant-isolated")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value(5));

        mockMvc.perform(get("/sample/rabbitmq/batch/tenant-records"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.successRecords", hasSize(3)))
                .andExpect(jsonPath("$.data.successRecords[0].orderId").value("ORD-OK-1"))
                .andExpect(jsonPath("$.data.successRecords[0].tenantId").value("tenant-alpha"))
                .andExpect(jsonPath("$.data.successRecords[1].orderId").value("ORD-OK-NONE"))
                .andExpect(jsonPath("$.data.successRecords[1].tenantId").doesNotExist())
                .andExpect(jsonPath("$.data.successRecords[2].orderId").value("ORD-OK-2"))
                .andExpect(jsonPath("$.data.successRecords[2].tenantId").value("tenant-beta"))
                .andExpect(jsonPath("$.data.successRecords[2].ignoreTenant").value(true))
                .andExpect(jsonPath("$.data.failedRecords", hasSize(1)))
                .andExpect(jsonPath("$.data.failedRecords[0].orderId").value("FAIL-ORD-1"))
                .andExpect(jsonPath("$.data.failedRecords[0].tenantId").value("tenant-fail"));

        mockMvc.perform(post("/sample/rabbitmq/batch/tenant-isolated")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.success").value(false));

        String invalidItemJson = """
                {
                  "items": [
                    {"orderId": "", "amount": -1.00, "tenantId": "tenant-bad"}
                  ]
                }
                """;
        mockMvc.perform(post("/sample/rabbitmq/batch/tenant-isolated")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidItemJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.success").value(false));

        mockMvc.perform(delete("/sample/rabbitmq/batch/clear"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        assertThat(batchTenantIsolationConsumer.getSuccessRecords()).isEmpty();
        assertThat(batchTenantIsolationConsumer.getFailedRecords()).isEmpty();
    }

    @Test
    @DisplayName("直传整批 List<Message> 至 BatchTenantIsolationConsumer 验证混排逐条上下文隔离与外部现场恢复")
    void shouldIsolateDirectBatchMessageListAndRestoreOuterContext() {
        MDC.put(MessageContextPropagator.MDC_TRACE_ID_KEY, "outer-e2e-trace");
        TenantContextHolder.setTenantId("outer-e2e-tenant");
        UserContextHolder.set("outer-e2e-user");

        Message m1 = buildJsonOrderMessage("ORD-B1", "10.50", "tr-1", "ten-1", "usr-1", false);
        Message m2 = buildJsonOrderMessage("DUP-B2", "11.00", "tr-2", "ten-2", "usr-2", false);
        Message m3 = buildJsonOrderMessage("ORD-B3", "30.00", "tr-3", null, null, false);

        batchTenantIsolationConsumer.onBatchMessages(List.of(m1, m2, m3));

        assertThat(batchTenantIsolationConsumer.getSuccessRecords()).hasSize(2);
        assertThat(batchTenantIsolationConsumer.getSuccessRecords().get(0).tenantId()).isEqualTo("ten-1");
        assertThat(batchTenantIsolationConsumer.getSuccessRecords().get(1).tenantId()).isNull();
        assertThat(batchTenantIsolationConsumer.getFailedRecords()).isEmpty();

        assertThat(MDC.get(MessageContextPropagator.MDC_TRACE_ID_KEY)).isEqualTo("outer-e2e-trace");
        assertThat(TenantContextHolder.getTenantId()).isEqualTo("outer-e2e-tenant");
        assertThat(UserContextHolder.get()).isEqualTo("outer-e2e-user");
    }

    private Message buildJsonOrderMessage(
            String orderId,
            String amount,
            String traceId,
            String tenantId,
            String userId,
            boolean ignoreTenant) {
        String json = "{\"orderId\":\"" + orderId + "\",\"amount\":" + amount + ",\"userId\":\""
                + (userId != null ? userId : "guest") + "\"}";
        MessageProperties props = new MessageProperties();
        props.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        props.setContentEncoding(StandardCharsets.UTF_8.name());
        props.setHeader(MessageHeaderConstants.TRACE_ID, traceId);
        props.setHeader(MessageHeaderConstants.BIZ_KEY, orderId);
        if (tenantId != null) {
            props.setHeader(MessageHeaderConstants.TENANT_ID, tenantId);
        }
        if (userId != null) {
            props.setHeader(MessageHeaderConstants.USER_ID, userId);
        }
        if (ignoreTenant) {
            props.setHeader(MessageHeaderConstants.IGNORE_TENANT, "true");
        }
        return new Message(json.getBytes(StandardCharsets.UTF_8), props);
    }
}
