package io.github.atengk.boot.sample.rabbitmq;

import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.boot.rabbitmq.manager.RabbitContainerManager;
import io.github.atengk.boot.sample.rabbitmq.config.RabbitScenarioTopologyConfig;
import io.github.atengk.boot.sample.rabbitmq.consumer.OrderConsumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * RabbitContainerController 离线端到端集成测试
 *
 * @author Ateng
 * @since 2026-11-15
 */
@SpringBootTest(classes = SampleRabbitmqApplication.class)
@AutoConfigureMockMvc
@DisplayName("RabbitContainerController 离线端到端集成测试")
class RabbitContainerControllerE2eTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private RabbitContainerManager rabbitContainerManager;

    @Autowired
    private OrderConsumer orderConsumer;

    @AfterEach
    void restoreContainersAndCleanContext() {
        rabbitContainerManager.startAll();
        rabbitContainerManager.scaleConcurrency(RabbitScenarioTopologyConfig.QUEUE_ORDER_CREATE, 1, 4);
        rabbitContainerManager.updatePrefetchCount(RabbitScenarioTopologyConfig.QUEUE_ORDER_CREATE, 20);
        orderConsumer.clear();
        UserContextHolder.clear();
        TenantContextHolder.clear();
        MDC.clear();
    }

    @Test
    @DisplayName("全链路验证容器列表、单容器启停、并发调整、预取修改及队列深度安全观测")
    void shouldExecuteContainerLifecycleAndObservationEndToEnd() throws Exception {
        mockMvc.perform(get("/sample/rabbitmq/container/list"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data", hasSize(greaterThanOrEqualTo(1))));

        mockMvc.perform(post("/sample/rabbitmq/container/stop")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"target\":\"" + RabbitScenarioTopologyConfig.QUEUE_ORDER_CREATE + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.running").value(false));

        mockMvc.perform(post("/sample/rabbitmq/container/start")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"target\":\"" + RabbitScenarioTopologyConfig.QUEUE_ORDER_CREATE + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.running").value(true));

        mockMvc.perform(post("/sample/rabbitmq/container/scale")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"target\":\"" + RabbitScenarioTopologyConfig.QUEUE_ORDER_CREATE
                                + "\",\"concurrentConsumers\":2,\"maxConcurrentConsumers\":4}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.running").value(true));

        mockMvc.perform(post("/sample/rabbitmq/container/prefetch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"target\":\"" + RabbitScenarioTopologyConfig.QUEUE_ORDER_CREATE
                                + "\",\"prefetchCount\":32}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.prefetchCount").value(32));

        mockMvc.perform(get("/sample/rabbitmq/container/queue-status")
                        .param("queueName", RabbitScenarioTopologyConfig.QUEUE_ORDER_CREATE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.queueName").value(RabbitScenarioTopologyConfig.QUEUE_ORDER_CREATE))
                .andExpect(jsonPath("$.data.exists").value(true))
                .andExpect(jsonPath("$.data.consumerCount", greaterThanOrEqualTo(1)));

        mockMvc.perform(get("/sample/rabbitmq/container/queue-status")
                        .param("queueName", "sample.missing.queue"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.exists").value(false))
                .andExpect(jsonPath("$.data.messageCount").value(0))
                .andExpect(jsonPath("$.data.consumerCount").value(0));

        mockMvc.perform(post("/sample/rabbitmq/container/stop-all"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        mockMvc.perform(post("/sample/rabbitmq/container/start-all"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }
}
