package io.github.atengk.boot.sample.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.atengk.boot.sample.core.dto.ExchangeRateRequest;
import io.github.atengk.boot.sample.core.dto.OrderPipelineRequest;
import io.github.atengk.boot.sample.core.dto.RateLimitRequest;
import io.github.atengk.boot.sample.core.service.CoreResilienceScenarioService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * CoreResilienceAndPipelineController 端到端弹性治理与业务管道 Web 集成测试
 * 覆盖 RetryUtil 汇率重试降级、滑动窗口防刷、令牌桶限流、IdempotentException 幂等拦截及 Pipeline 电商提单责任链短路中断，并在每项测试完成后彻底清理现场
 *
 * @author Ateng
 * @since 2026-11-07
 */
@SpringBootTest
@AutoConfigureMockMvc
class CoreResilienceAndPipelineControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private CoreResilienceScenarioService resilienceScenarioService;

    @AfterEach
    void tearDown() {
        // 无条件清理幂等 Token 缓存与限流计数器，保证用例间状态绝对隔离
        resilienceScenarioService.resetResilienceState();
    }

    @Test
    @DisplayName("1. 验证 RetryUtil 弹性重试机制：网关临时网络波动重试 1 次后成功")
    void testExchangeRate_SuccessWithRetry() throws Exception {
        ExchangeRateRequest request = ExchangeRateRequest.builder()
                .fromCurrency("USD")
                .toCurrency("CNY")
                .simulateFailures(1)
                .build();

        mockMvc.perform(post("/sample/core/resilience/retry-exchange-rate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(200)))
                .andExpect(jsonPath("$.success", is(true)))
                .andExpect(jsonPath("$.data.fallbackTriggered", is(false)))
                .andExpect(jsonPath("$.data.currencyPair", is("USD/CNY")))
                .andExpect(jsonPath("$.data.attempts", is(2)))
                .andExpect(jsonPath("$.data.source", is("LIVE_GATEWAY")))
                .andExpect(jsonPath("$.data.rate", notNullValue()))
                .andExpect(jsonPath("$.data.retryLogs", notNullValue()));
    }

    @Test
    @DisplayName("2. 验证 RetryUtil 重试耗尽降级：达到最大重试次数后平滑触发 Fallback 保底汇率")
    void testExchangeRate_ExhaustedFallback() throws Exception {
        ExchangeRateRequest request = ExchangeRateRequest.builder()
                .fromCurrency("USD")
                .toCurrency("CNY")
                .simulateFailures(5)
                .build();

        mockMvc.perform(post("/sample/core/resilience/retry-exchange-rate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(200)))
                .andExpect(jsonPath("$.success", is(true)))
                .andExpect(jsonPath("$.data.fallbackTriggered", is(true)))
                .andExpect(jsonPath("$.data.currencyPair", is("USD/CNY")))
                .andExpect(jsonPath("$.data.attempts", is(3)))
                .andExpect(jsonPath("$.data.source", is("FALLBACK_CACHE")))
                .andExpect(jsonPath("$.data.rate", is(7.0000)))
                .andExpect(jsonPath("$.data.retryLogs", notNullValue()));
    }

    @Test
    @DisplayName("3. 验证进程内流控与流量整形：单机滑动窗口、令牌桶与首次幂等校验均正常放行")
    void testRateLimit_NormalPass() throws Exception {
        RateLimitRequest request = RateLimitRequest.builder()
                .clientId("client-test-001")
                .reqToken("REQ_TOKEN_NORMAL_1001")
                .triggerIdempotentCheck(true)
                .build();

        mockMvc.perform(post("/sample/core/resilience/rate-limit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(200)))
                .andExpect(jsonPath("$.success", is(true)))
                .andExpect(jsonPath("$.data.clientId", is("client-test-001")))
                .andExpect(jsonPath("$.data.slidingWindowAllowed", is(true)))
                .andExpect(jsonPath("$.data.tokenBucketAcquired", is(true)))
                .andExpect(jsonPath("$.data.idempotentPassed", is(true)))
                .andExpect(jsonPath("$.data.slidingWindowCount", is("1")));
    }

    @Test
    @DisplayName("4. 验证 IdempotentException 幂等防重拦截：相同 reqToken 重复请求被全局异常拦截并转译为 40003")
    void testRateLimit_IdempotentDuplicated() throws Exception {
        RateLimitRequest request = RateLimitRequest.builder()
                .clientId("client-test-002")
                .reqToken("REQ_TOKEN_DUPLICATE_2001")
                .triggerIdempotentCheck(true)
                .build();

        // 1. 首次提交：正常放行
        mockMvc.perform(post("/sample/core/resilience/rate-limit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(200)))
                .andExpect(jsonPath("$.success", is(true)));

        // 2. 二次提交相同 reqToken：触发 IdempotentException 防重拦截
        mockMvc.perform(post("/sample/core/resilience/rate-limit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(40003)))
                .andExpect(jsonPath("$.success", is(false)))
                .andExpect(jsonPath("$.msg", containsString("短时间内重复提交请求")));
    }

    @Test
    @DisplayName("5. 验证 Pipeline 业务管道全流程放行：参数合规、风控放行、库存充足且完成优惠计算")
    void testOrderPipeline_Success() throws Exception {
        OrderPipelineRequest request = OrderPipelineRequest.builder()
                .orderNo("ORD202611070001")
                .userId(1001L)
                .orderAmount(new BigDecimal("200.00"))
                .skuCode("SKU-8888")
                .buyCount(2)
                .availableStock(20)
                .build();

        mockMvc.perform(post("/sample/core/resilience/order-pipeline")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(200)))
                .andExpect(jsonPath("$.success", is(true)))
                .andExpect(jsonPath("$.data.orderNo", is("ORD202611070001")))
                .andExpect(jsonPath("$.data.passed", is(true)))
                .andExpect(jsonPath("$.data.terminated", is(false)))
                .andExpect(jsonPath("$.data.discountAmount", is(20)))
                .andExpect(jsonPath("$.data.auditTrail", notNullValue()));
    }

    @Test
    @DisplayName("6. 验证 Pipeline 风控短路中断：黑名单用户 userId=9999 在风控合规阶段提前熔断终止")
    void testOrderPipeline_RiskInterrupted() throws Exception {
        OrderPipelineRequest request = OrderPipelineRequest.builder()
                .orderNo("ORD202611070002")
                .userId(9999L)
                .orderAmount(new BigDecimal("200.00"))
                .skuCode("SKU-8888")
                .buyCount(1)
                .availableStock(10)
                .build();

        mockMvc.perform(post("/sample/core/resilience/order-pipeline")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(200)))
                .andExpect(jsonPath("$.success", is(true)))
                .andExpect(jsonPath("$.data.orderNo", is("ORD202611070002")))
                .andExpect(jsonPath("$.data.passed", is(false)))
                .andExpect(jsonPath("$.data.terminated", is(true)))
                .andExpect(jsonPath("$.data.terminatedStageName", containsString("风控")))
                .andExpect(jsonPath("$.data.interruptReason", containsString("黑名单")));
    }

    @Test
    @DisplayName("7. 验证 Pipeline 库存不足短路中断：购买数量大于可用库存时在库存检查阶段提前熔断终止")
    void testOrderPipeline_StockInterrupted() throws Exception {
        OrderPipelineRequest request = OrderPipelineRequest.builder()
                .orderNo("ORD202611070003")
                .userId(1001L)
                .orderAmount(new BigDecimal("200.00"))
                .skuCode("SKU-8888")
                .buyCount(10)
                .availableStock(5)
                .build();

        mockMvc.perform(post("/sample/core/resilience/order-pipeline")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(200)))
                .andExpect(jsonPath("$.success", is(true)))
                .andExpect(jsonPath("$.data.orderNo", is("ORD202611070003")))
                .andExpect(jsonPath("$.data.passed", is(false)))
                .andExpect(jsonPath("$.data.terminated", is(true)))
                .andExpect(jsonPath("$.data.terminatedStageName", containsString("库存")))
                .andExpect(jsonPath("$.data.interruptReason", containsString("库存不足")));
    }
}
