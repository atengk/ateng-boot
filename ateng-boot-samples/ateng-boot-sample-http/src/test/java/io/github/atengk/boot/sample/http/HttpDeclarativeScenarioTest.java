package io.github.atengk.boot.sample.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.atengk.boot.sample.http.client.OrderServiceClientFallback;
import io.github.atengk.boot.sample.http.dto.UserRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.TestSocketUtils;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HttpDeclarativeClientController 端到端回环集成场景测试
 * 验证声明式接口 RPC、方法级超时控制与本地优雅降级
 *
 * @author Ateng
 * @since 2026-11-09
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
@AutoConfigureMockMvc
public class HttpDeclarativeScenarioTest {

    static int port = TestSocketUtils.findAvailableTcpPort();

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("server.port", () -> port);
        registry.add("sample.user-service.url", () -> "http://localhost:" + port + "/mock/provider");
        registry.add("sample.order-service.url", () -> "http://localhost:" + port + "/mock/provider");
        registry.add("sample.wechat-pay.url", () -> "http://localhost:" + port + "/mock/provider/wechat");
        registry.add("sample.flaky-service.url", () -> "http://localhost:" + port);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("场景 1: 声明式客户端 GET 查询用户详情")
    void testDeclarativeGetUserById() throws Exception {
        mockMvc.perform(get("/sample/http/declarative/user/{id}", 1001L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.id").value(1001))
                .andExpect(jsonPath("$.data.username").value("user_1001"))
                .andExpect(jsonPath("$.data.role").value("ADMIN"))
                .andExpect(jsonPath("$.data.status").value("ACTIVE"));
    }

    @Test
    @DisplayName("场景 2: 声明式客户端 POST 创建用户")
    void testDeclarativeCreateUser() throws Exception {
        UserRequest request = UserRequest.builder()
                .username("DeclarativeDev")
                .email("dec@example.com")
                .role("LEAD")
                .build();

        mockMvc.perform(post("/sample/http/declarative/user")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.id").value(2001))
                .andExpect(jsonPath("$.data.username").value("DeclarativeDev"))
                .andExpect(jsonPath("$.data.role").value("LEAD"));
    }

    @Test
    @DisplayName("场景 3: 声明式客户端正常查询订单详情")
    void testDeclarativeGetOrderById() throws Exception {
        mockMvc.perform(get("/sample/http/declarative/order/{orderId}", "ORD_NORMAL_888"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.orderId").value("ORD_NORMAL_888"))
                .andExpect(jsonPath("$.data.status").value("SUCCESS"));
    }

    @Test
    @DisplayName("场景 4: 声明式客户端查询订单状态")
    void testDeclarativeGetOrderStatus() throws Exception {
        mockMvc.perform(get("/sample/http/declarative/order/{orderId}/status", "ORD_STATUS_999"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.orderId").value("ORD_STATUS_999"))
                .andExpect(jsonPath("$.data.status").value("COMPLETED"));
    }

    @Test
    @DisplayName("场景 5: 慢调用触发 @HttpTimeout 并自动执行 Fallback 本地优雅降级")
    void testDeclarativeTimeoutFallback() throws Exception {
        mockMvc.perform(get("/sample/http/declarative/order/fallback/timeout/{orderId}", "slow-order-123"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.orderId").value("slow-order-123"))
                .andExpect(jsonPath("$.data.status").value(OrderServiceClientFallback.STATUS_DEGRADED))
                .andExpect(jsonPath("$.data.message").value(containsString("优雅降级")));
    }

    @Test
    @DisplayName("场景 6: 下游 5xx 故障触发 Fallback 本地优雅降级")
    void testDeclarativeErrorFallback() throws Exception {
        mockMvc.perform(get("/sample/http/declarative/order/fallback/error/{orderId}", "fail-order-456"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.orderId").value("fail-order-456"))
                .andExpect(jsonPath("$.data.status").value(OrderServiceClientFallback.STATUS_DEGRADED))
                .andExpect(jsonPath("$.data.message").value(containsString("优雅降级")));
    }
}
