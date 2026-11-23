package io.github.atengk.boot.sample.rabbitmq;

import io.github.atengk.boot.rabbitmq.manager.RabbitContainerManager;
import io.github.atengk.boot.rabbitmq.manager.RabbitContainerStatus;
import io.github.atengk.boot.rabbitmq.manager.RabbitQueueStatus;
import io.github.atengk.boot.sample.rabbitmq.controller.RabbitContainerController;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * RabbitContainerController WebMvc 切片测试
 *
 * @author Ateng
 * @since 2026-11-15
 */
@WebMvcTest(controllers = RabbitContainerController.class)
@DisplayName("RabbitContainerController WebMvc 切片测试")
class RabbitContainerControllerWebTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private RabbitContainerManager rabbitContainerManager;

    @Test
    @DisplayName("容器列表与单容器状态查询接口返回 Result 视图")
    void shouldListAndInspectContainerStatuses() throws Exception {
        RabbitContainerStatus orderStatus = new RabbitContainerStatus(
                "listener-order",
                List.of("sample.order.create.queue"),
                true,
                true,
                1,
                250,
                "SimpleMessageListenerContainer"
        );
        when(rabbitContainerManager.getContainerStatuses()).thenReturn(List.of(orderStatus));
        when(rabbitContainerManager.getContainerStatus("sample.order.create.queue")).thenReturn(orderStatus);

        mockMvc.perform(get("/sample/rabbitmq/container/list"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data[0].listenerId").value("listener-order"))
                .andExpect(jsonPath("$.data[0].running").value(true));

        mockMvc.perform(get("/sample/rabbitmq/container/status")
                        .param("target", "sample.order.create.queue"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.prefetchCount").value(250));
    }

    @Test
    @DisplayName("启停、扩缩容、预取修改与队列观测端点正确委托门面")
    void shouldDelegateContainerControlAndQueueStatusEndpoints() throws Exception {
        RabbitContainerStatus stopped = new RabbitContainerStatus(
                "listener-order", List.of("sample.order.create.queue"), false, true, 0, 250, "SimpleMessageListenerContainer");
        RabbitContainerStatus scaled = new RabbitContainerStatus(
                "listener-order", List.of("sample.order.create.queue"), true, true, 2, 64, "SimpleMessageListenerContainer");
        when(rabbitContainerManager.getContainerStatus("sample.order.create.queue"))
                .thenReturn(stopped, scaled, scaled);
        when(rabbitContainerManager.getContainerStatuses()).thenReturn(List.of(scaled));
        when(rabbitContainerManager.getQueueStatus("sample.order.create.queue"))
                .thenReturn(new RabbitQueueStatus("sample.order.create.queue", 12L, 2, true));

        mockMvc.perform(post("/sample/rabbitmq/container/stop")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"target\":\"sample.order.create.queue\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.running").value(false));
        verify(rabbitContainerManager).stop("sample.order.create.queue");

        mockMvc.perform(post("/sample/rabbitmq/container/start")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"target\":\"sample.order.create.queue\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.running").value(true));
        verify(rabbitContainerManager).start("sample.order.create.queue");

        mockMvc.perform(post("/sample/rabbitmq/container/scale")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"target\":\"sample.order.create.queue\",\"concurrentConsumers\":2,\"maxConcurrentConsumers\":4}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
        verify(rabbitContainerManager).scaleConcurrency("sample.order.create.queue", 2, 4);

        mockMvc.perform(post("/sample/rabbitmq/container/prefetch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"target\":\"sample.order.create.queue\",\"prefetchCount\":64}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.prefetchCount").value(64));
        verify(rabbitContainerManager).updatePrefetchCount("sample.order.create.queue", 64);

        mockMvc.perform(post("/sample/rabbitmq/container/stop-all"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
        verify(rabbitContainerManager).stopAll();

        mockMvc.perform(post("/sample/rabbitmq/container/start-all"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
        verify(rabbitContainerManager).startAll();

        mockMvc.perform(get("/sample/rabbitmq/container/queue-status")
                        .param("queueName", "sample.order.create.queue"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.messageCount").value(12))
                .andExpect(jsonPath("$.data.consumerCount").value(2))
                .andExpect(jsonPath("$.data.exists").value(true));
    }

    @Test
    @DisplayName("JSR-303 参数校验拦截非法并发范围与空目标请求")
    void shouldRejectInvalidContainerRequests() throws Exception {
        mockMvc.perform(post("/sample/rabbitmq/container/scale")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"target\":\"sample.order.create.queue\",\"concurrentConsumers\":4,\"maxConcurrentConsumers\":2}"))
                .andExpect(status().is4xxClientError());

        mockMvc.perform(post("/sample/rabbitmq/container/prefetch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"target\":\"\",\"prefetchCount\":0}"))
                .andExpect(status().is4xxClientError());

        verifyNoInteractions(rabbitContainerManager);
    }
}
