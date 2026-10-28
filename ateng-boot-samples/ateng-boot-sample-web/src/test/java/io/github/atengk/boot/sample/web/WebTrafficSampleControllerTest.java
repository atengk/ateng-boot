package io.github.atengk.boot.sample.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.boot.sample.web.dto.TrafficOrderDTO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 流量控制与幂等防重端到端集成测试
 * 覆盖 @RateLimiter 多维限流与 @Idempotent 幂等防抖
 *
 * @author Ateng
 * @since 2026-10-19
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("流量控制与防重幂等端到端测试")
class WebTrafficSampleControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    @AfterEach
    void cleanContext() {
        UserContextHolder.clear();
        MDC.clear();
    }

    @Test
    @DisplayName("1. @RateLimiter 默认接口限流：窗口期内前 2 次成功，第 3 次被限流拦截")
    void shouldLimitDefaultRate() throws Exception {
        // 第 1 次
        mockMvc.perform(get("/api/sample/traffic/ratelimit/default"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value("default ok"));

        // 第 2 次
        mockMvc.perform(get("/api/sample/traffic/ratelimit/default"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        // 第 3 次超限拦截
        mockMvc.perform(get("/api/sample/traffic/ratelimit/default"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(429))
                .andExpect(jsonPath("$.msg").value("默认接口限流拦截"));
    }

    @Test
    @DisplayName("2. @RateLimiter IP 维度限流：相同 IP 前 2 次成功，第 3 次拦截")
    void shouldLimitByClientIp() throws Exception {
        // 第 1 次
        mockMvc.perform(get("/api/sample/traffic/ratelimit/ip"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        // 第 2 次
        mockMvc.perform(get("/api/sample/traffic/ratelimit/ip"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        // 第 3 次
        mockMvc.perform(get("/api/sample/traffic/ratelimit/ip"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(429))
                .andExpect(jsonPath("$.msg").value("IP 限流拦截"));
    }

    @Test
    @DisplayName("3. @RateLimiter 用户维度限流：同一用户超限拦截，不同用户互不影响")
    void shouldLimitByUser() throws Exception {
        UserContextHolder.set("user-1001");
        // 第 1 次
        mockMvc.perform(get("/api/sample/traffic/ratelimit/user"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        // 第 2 次
        mockMvc.perform(get("/api/sample/traffic/ratelimit/user"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        // 第 3 次超限拦截
        mockMvc.perform(get("/api/sample/traffic/ratelimit/user"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(429))
                .andExpect(jsonPath("$.msg").value("用户限流拦截"));

        // 切换不同用户放行
        UserContextHolder.set("user-1002");
        mockMvc.perform(get("/api/sample/traffic/ratelimit/user"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    @DisplayName("4. @RateLimiter SpEL 复合限流：IP + 动态参数复合维度")
    void shouldLimitByCompositeKey() throws Exception {
        // tenant-A 前 2 次放行
        mockMvc.perform(get("/api/sample/traffic/ratelimit/composite").param("tenantId", "tenant-A"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        mockMvc.perform(get("/api/sample/traffic/ratelimit/composite").param("tenantId", "tenant-A"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        // tenant-A 第 3 次被拦截
        mockMvc.perform(get("/api/sample/traffic/ratelimit/composite").param("tenantId", "tenant-A"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(429))
                .andExpect(jsonPath("$.msg").value("复合租户限流拦截"));

        // tenant-B 正常放行
        mockMvc.perform(get("/api/sample/traffic/ratelimit/composite").param("tenantId", "tenant-B"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value("tenant: tenant-B"));
    }

    @Test
    @DisplayName("5. @Idempotent SpEL 业务键防重：相同业务订单号重复提交被切面拦截")
    void shouldPreventDuplicateSubmissionWithSpelKey() throws Exception {
        String orderNo = "ORD_TRAFFIC_" + System.currentTimeMillis();
        TrafficOrderDTO dto = new TrafficOrderDTO();
        dto.setOrderNo(orderNo);
        dto.setAmount(new BigDecimal("99.9"));
        String json = objectMapper.writeValueAsString(dto);

        // 首次提交成功放行
        mockMvc.perform(post("/api/sample/traffic/idempotent/order")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value(orderNo));

        // 立即重复提交被拦截
        mockMvc.perform(post("/api/sample/traffic/idempotent/order")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(40003))
                .andExpect(jsonPath("$.msg").value("订单处理中，请勿重复提交"));
    }

    @Test
    @DisplayName("6. @Idempotent 通用按钮指纹哈希防抖：相同请求特征拦截，异参放行")
    void shouldDebounceButtonWithAutoFingerprint() throws Exception {
        String tokenA = "token_" + System.currentTimeMillis();
        String tokenB = tokenA + "_diff";

        // 首次点击放行
        mockMvc.perform(post("/api/sample/traffic/idempotent/button")
                        .param("formToken", tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value("submitted: " + tokenA));

        // 快速连击相同表单指纹被拦截
        mockMvc.perform(post("/api/sample/traffic/idempotent/button")
                        .param("formToken", tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(40003))
                .andExpect(jsonPath("$.msg").value("操作过于频繁，请稍后再试"));

        // 提交不同参数放行
        mockMvc.perform(post("/api/sample/traffic/idempotent/button")
                        .param("formToken", tokenB))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    @DisplayName("7. @Idempotent 异常自动释放锁：业务失败时锁立即释放，无需等待超时即可无缝重试")
    void shouldReleaseLockImmediatelyOnBusinessException() throws Exception {
        String bizId = "BIZ_RETRY_" + System.currentTimeMillis();

        // 首次请求触发可恢复业务异常
        mockMvc.perform(post("/api/sample/traffic/idempotent/fail-retry")
                        .param("bizId", bizId)
                        .param("fail", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.msg").value("可恢复业务校验失败"));

        // 验证锁已被主动释放：无需等待 5 秒超时，立即修正参数重试即可成功
        mockMvc.perform(post("/api/sample/traffic/idempotent/fail-retry")
                        .param("bizId", bizId)
                        .param("fail", "false"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value("success: " + bizId));
    }
}
