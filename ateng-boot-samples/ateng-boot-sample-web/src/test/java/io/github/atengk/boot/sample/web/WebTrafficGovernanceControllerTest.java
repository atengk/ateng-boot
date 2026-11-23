package io.github.atengk.boot.sample.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.boot.sample.web.dto.TrafficOrderDTO;
import io.github.atengk.boot.web.ratelimit.store.RateLimiterStore;
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

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 接口防刷限流与防重幂等治理端到端集成测试
 * 覆盖 @RateLimiter 多维限流与 @Idempotent 幂等防抖
 *
 * @author Ateng
 * @since 2026-11-07
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("接口防刷限流与防重幂等治理端到端测试")
class WebTrafficGovernanceControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private RateLimiterStore rateLimiterStore;

    @BeforeEach
    @AfterEach
    void cleanContext() {
        UserContextHolder.clear();
        TenantContextHolder.clear();
        MDC.clear();
        if (rateLimiterStore instanceof io.github.atengk.boot.web.ratelimit.store.InMemoryRateLimiterStore memStore) {
            memStore.clear();
        } else if (rateLimiterStore != null) {
            rateLimiterStore.reset("rate_limit:default:/sample/web/traffic/ratelimit/default");
            rateLimiterStore.reset("rate_limit:ip:203.0.113.50");
        }
    }

    @Test
    @DisplayName("1. @RateLimiter 默认接口限流：窗口期内前 2 次成功，第 3 次被限流拦截")
    void shouldLimitDefaultRate() throws Exception {
        // 第 1 次
        mockMvc.perform(get("/sample/web/traffic/ratelimit/default"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value("全局接口访问成功"));

        // 第 2 次
        mockMvc.perform(get("/sample/web/traffic/ratelimit/default"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        // 第 3 次超限拦截
        mockMvc.perform(get("/sample/web/traffic/ratelimit/default"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(429))
                .andExpect(jsonPath("$.msg").value("默认接口限流拦截"));
    }

    @Test
    @DisplayName("2. @RateLimiter 客户端 IP 维度防刷限流：相同客户端 IP 前 2 次成功，第 3 次被短信防刷拦截")
    void shouldLimitByClientIp() throws Exception {
        // 第 1 次
        mockMvc.perform(get("/sample/web/traffic/ratelimit/ip"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value(containsString("验证码已发送至客户端 IP")));

        // 第 2 次
        mockMvc.perform(get("/sample/web/traffic/ratelimit/ip"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        // 第 3 次
        mockMvc.perform(get("/sample/web/traffic/ratelimit/ip"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(429))
                .andExpect(jsonPath("$.msg").value("短信验证码请求过于频繁，请稍后再试"));
    }

    @Test
    @DisplayName("3. @RateLimiter 用户维度限流：同一用户超限拦截，不同用户互不影响")
    void shouldLimitByUser() throws Exception {
        UserContextHolder.set("user-1001");
        // 第 1 次
        mockMvc.perform(get("/sample/web/traffic/ratelimit/user"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value("用户资料操作成功: user-1001"));

        // 第 2 次
        mockMvc.perform(get("/sample/web/traffic/ratelimit/user"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        // 第 3 次超限拦截
        mockMvc.perform(get("/sample/web/traffic/ratelimit/user"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(429))
                .andExpect(jsonPath("$.msg").value("用户操作过于频繁，请稍后再试"));

        // 切换不同用户放行
        UserContextHolder.set("user-1002");
        mockMvc.perform(get("/sample/web/traffic/ratelimit/user"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value("用户资料操作成功: user-1002"));
    }

    @Test
    @DisplayName("4. @RateLimiter SpEL 复合限流：IP + 动态参数复合维度")
    void shouldLimitByCompositeKey() throws Exception {
        // tenant-A 前 2 次放行
        mockMvc.perform(get("/sample/web/traffic/ratelimit/composite").param("tenantId", "tenant-A"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value("租户配额请求成功: tenant-A"));

        mockMvc.perform(get("/sample/web/traffic/ratelimit/composite").param("tenantId", "tenant-A"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        // tenant-A 第 3 次被拦截
        mockMvc.perform(get("/sample/web/traffic/ratelimit/composite").param("tenantId", "tenant-A"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(429))
                .andExpect(jsonPath("$.msg").value("复合租户限流拦截"));

        // tenant-B 正常放行
        mockMvc.perform(get("/sample/web/traffic/ratelimit/composite").param("tenantId", "tenant-B"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value("租户配额请求成功: tenant-B"));
    }

    @Test
    @DisplayName("5. @RateLimiter SaaS 多租户原生流量隔离限流：租户隔离独立计数，超限返回 429 业务错误与 Retry-After 标头")
    void shouldEnforceTenantRateLimiting() throws Exception {
        // 租户 tenant_1 连续请求 2 次均成功放行
        TenantContextHolder.setTenantId("tenant_1");
        mockMvc.perform(get("/sample/web/traffic/ratelimit/tenant"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value("tenant=tenant_1"));

        mockMvc.perform(get("/sample/web/traffic/ratelimit/tenant"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value("tenant=tenant_1"));

        // 租户 tenant_1 第 3 次触发超限熔断
        mockMvc.perform(get("/sample/web/traffic/ratelimit/tenant"))
                .andExpect(status().isOk())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.code").value(429))
                .andExpect(jsonPath("$.msg").value(containsString("当前租户请求过于频繁，请稍后再试")));

        // 切换至租户 tenant_2，验证完全隔离
        TenantContextHolder.setTenantId("tenant_2");
        mockMvc.perform(get("/sample/web/traffic/ratelimit/tenant"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value("tenant=tenant_2"));
    }

    @Test
    @DisplayName("6. @Idempotent SpEL 业务键防重：相同业务订单号重复提交被切面精准拦截")
    void shouldPreventDuplicateSubmissionWithSpelKey() throws Exception {
        String orderNo = "ORD_TRAFFIC_" + System.currentTimeMillis();
        TrafficOrderDTO dto = new TrafficOrderDTO();
        dto.setOrderNo(orderNo);
        dto.setAmount(new BigDecimal("99.9"));
        String json = objectMapper.writeValueAsString(dto);

        // 首次提交成功放行
        mockMvc.perform(post("/sample/web/traffic/idempotent/order")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value(orderNo));

        // 立即重复提交被拦截
        mockMvc.perform(post("/sample/web/traffic/idempotent/order")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(40003))
                .andExpect(jsonPath("$.msg").value("订单处理中，请勿重复提交"));
    }

    @Test
    @DisplayName("7. @Idempotent 通用按钮指纹哈希防抖：相同请求特征拦截，异参放行")
    void shouldDebounceButtonWithAutoFingerprint() throws Exception {
        String tokenA = "token_" + System.currentTimeMillis();
        String tokenB = tokenA + "_diff";

        // 首次点击放行
        mockMvc.perform(post("/sample/web/traffic/idempotent/button")
                        .param("formToken", tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value("表单提交成功: " + tokenA));

        // 快速连击相同表单指纹被拦截
        mockMvc.perform(post("/sample/web/traffic/idempotent/button")
                        .param("formToken", tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(40003))
                .andExpect(jsonPath("$.msg").value("操作过于频繁，请稍后再试"));

        // 提交不同参数放行
        mockMvc.perform(post("/sample/web/traffic/idempotent/button")
                        .param("formToken", tokenB))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    @DisplayName("8. @Idempotent 优惠券精准防重：相同券码防重复领取")
    void shouldPreventDuplicateCouponClaim() throws Exception {
        String couponCode = "COUPON_" + System.currentTimeMillis();

        mockMvc.perform(post("/sample/web/traffic/idempotent/coupon")
                        .param("couponCode", couponCode))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value("优惠券领取成功: " + couponCode));

        mockMvc.perform(post("/sample/web/traffic/idempotent/coupon")
                        .param("couponCode", couponCode))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(40003))
                .andExpect(jsonPath("$.msg").value("领券操作过于频繁，请稍后再试"));
    }

    @Test
    @DisplayName("9. @Idempotent 异常自动释放锁：业务失败时锁立即释放，无需等待超时即可无缝重试")
    void shouldReleaseLockImmediatelyOnBusinessException() throws Exception {
        String bizId = "BIZ_RETRY_" + System.currentTimeMillis();

        // 首次请求触发可恢复业务异常
        mockMvc.perform(post("/sample/web/traffic/idempotent/fail-retry")
                        .param("bizId", bizId)
                        .param("fail", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.msg").value("可恢复业务校验失败"));

        // 验证锁已被主动释放：无需等待 5 秒超时，立即修正参数重试即可成功
        mockMvc.perform(post("/sample/web/traffic/idempotent/fail-retry")
                        .param("bizId", bizId)
                        .param("fail", "false"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value("success: " + bizId));
    }

    @Test
    @DisplayName("10. @Idempotent 锁状态查询：isLocked 正确反映加锁中与未锁定状态")
    void shouldCheckIdempotentLockStatus() throws Exception {
        String couponCode = "STATUS_COUPON_" + System.currentTimeMillis();
        String fullKey = "idempotent:coupon:claim:" + couponCode;

        // 加锁前状态应为 false
        mockMvc.perform(get("/sample/web/traffic/idempotent/is-locked")
                        .param("key", fullKey))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value(false));

        // 触发领券加锁
        mockMvc.perform(post("/sample/web/traffic/idempotent/coupon")
                        .param("couponCode", couponCode))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        // 加锁后应为 true
        mockMvc.perform(get("/sample/web/traffic/idempotent/is-locked")
                        .param("key", fullKey))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value(true));
    }

    @Test
    @DisplayName("11. @RateLimiter 运维重置配额：超限后调用 reset 立即恢复放行")
    void shouldResetRateLimitQuota() throws Exception {
        // 请求两次触发限流
        mockMvc.perform(get("/sample/web/traffic/ratelimit/default"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/sample/web/traffic/ratelimit/default"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/sample/web/traffic/ratelimit/default"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(429));

        // 重置默认限流 key
        String defaultKey = "rate_limit:default:/sample/web/traffic/ratelimit/default";
        mockMvc.perform(post("/sample/web/traffic/ratelimit/reset")
                        .param("key", defaultKey))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        // 重置后应恢复放行
        mockMvc.perform(get("/sample/web/traffic/ratelimit/default"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value("全局接口访问成功"));
    }

    @Test
    @DisplayName("12. @RateLimiter IP 防伪造对抗：非授信源 IP 每次轮换伪造 X-Forwarded-For 依然按真实 RemoteAddr 精准限流")
    void shouldEnforceIpRateLimitAgainstSpoofedForwardedFor() throws Exception {
        String untrustedClientIp = "203.0.113.50";
        String[] spoofedHeaders = {"1.1.1.1, 10.0.0.1", "8.8.8.8", "198.51.100.250"};

        // 第 1 次：非授信源 203.0.113.50 伪造 X-Forwarded-For，忽略伪造头后放行并识别真实 RemoteAddr
        mockMvc.perform(get("/sample/web/traffic/ratelimit/ip")
                        .with(req -> {
                            req.setRemoteAddr(untrustedClientIp);
                            return req;
                        })
                        .header("X-Forwarded-For", spoofedHeaders[0]))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value("验证码已发送至客户端 IP: " + untrustedClientIp));

        // 第 2 次：更换伪造头后依然命中同一真实源 IP 限流桶放行
        mockMvc.perform(get("/sample/web/traffic/ratelimit/ip")
                        .with(req -> {
                            req.setRemoteAddr(untrustedClientIp);
                            return req;
                        })
                        .header("X-Forwarded-For", spoofedHeaders[1]))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        // 第 3 次：再度更换伪造 X-Forwarded-For，仍精准触发 429 IP 限流拦截
        mockMvc.perform(get("/sample/web/traffic/ratelimit/ip")
                        .with(req -> {
                            req.setRemoteAddr(untrustedClientIp);
                            return req;
                        })
                        .header("X-Forwarded-For", spoofedHeaders[2]))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(429))
                .andExpect(jsonPath("$.msg").value("短信验证码请求过于频繁，请稍后再试"));
    }

    @Test
    @DisplayName("13. @RateLimiter 剩余配额探针与标准配额响应头端到端闭环")
    void shouldExposeAvailablePermitsAndRateLimitHeaders() throws Exception {
        String key = "rate_limit:default:/sample/web/traffic/ratelimit/default";

        // 1. 初始查询剩余配额应为 2（多次查询不扣减）
        mockMvc.perform(get("/sample/web/traffic/ratelimit/permits")
                        .param("key", key)
                        .param("count", "2")
                        .param("time", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value(2));
        mockMvc.perform(get("/sample/web/traffic/ratelimit/permits")
                        .param("key", key)
                        .param("count", "2")
                        .param("time", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value(2));

        // 2. 第 1 次消费后：响应头剩余 1，探针查询同步为 1
        mockMvc.perform(get("/sample/web/traffic/ratelimit/default"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(header().string("X-RateLimit-Limit", "2"))
                .andExpect(header().string("X-RateLimit-Remaining", "1"));
        mockMvc.perform(get("/sample/web/traffic/ratelimit/permits")
                        .param("key", key)
                        .param("count", "2")
                        .param("time", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value(1));

        // 3. 第 2 次消费后剩余 0；第 3 次触发 429 阻断并返回 Remaining=0 与 Retry-After
        mockMvc.perform(get("/sample/web/traffic/ratelimit/default"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(header().string("X-RateLimit-Limit", "2"))
                .andExpect(header().string("X-RateLimit-Remaining", "0"));
        mockMvc.perform(get("/sample/web/traffic/ratelimit/default"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(429))
                .andExpect(header().string("X-RateLimit-Limit", "2"))
                .andExpect(header().string("X-RateLimit-Remaining", "0"))
                .andExpect(header().exists("Retry-After"));

        // 4. 调用 reset 后，探针立即恢复为完整配额 2
        mockMvc.perform(post("/sample/web/traffic/ratelimit/reset")
                        .param("key", key))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
        mockMvc.perform(get("/sample/web/traffic/ratelimit/permits")
                        .param("key", key)
                        .param("count", "2")
                        .param("time", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value(2));

        // 5. 传输边界第一道防线 JSR-303 参数校验（空白 key / 非正数 count）快速失败返回 400
        mockMvc.perform(get("/sample/web/traffic/ratelimit/permits")
                        .param("key", "  ")
                        .param("count", "2")
                        .param("time", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));
        mockMvc.perform(get("/sample/web/traffic/ratelimit/permits")
                        .param("key", key)
                        .param("count", "0")
                        .param("time", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));
    }
}
