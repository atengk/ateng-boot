package io.github.atengk.boot.web.ratelimit.aspect;

import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.web.ratelimit.annotation.LimitType;
import io.github.atengk.boot.web.ratelimit.annotation.RateLimiter;
import io.github.atengk.boot.web.ratelimit.store.InMemoryRateLimiterStore;
import io.github.atengk.boot.web.ratelimit.store.RateLimiterStore;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.lang.reflect.Method;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 限流防刷 AOP 切面单测
 *
 * @author Ateng
 * @since 2026-10-17
 */
@DisplayName("限流防刷 AOP 切面单测")
class RateLimiterAspectTest {

    private InMemoryRateLimiterStore store;
    private RateLimiterAspect aspect;
    private MockHttpServletRequest request;
    private MockHttpServletResponse response;

    @BeforeEach
    void setUp() {
        store = new InMemoryRateLimiterStore();
        aspect = new RateLimiterAspect(store);
        request = new MockHttpServletRequest("GET", "/api/test");
        response = new MockHttpServletResponse();
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request, response));
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
        UserContextHolder.remove();
        TenantContextHolder.clear();
        store.destroy();
    }

    @Test
    @DisplayName("限流切面正常放行与超限拦截 429 及写入 Retry-After 头")
    void shouldBlockAndSetRetryAfterHeader() throws Throwable {
        ProceedingJoinPoint joinPoint = mock(ProceedingJoinPoint.class);
        MethodSignature signature = mock(MethodSignature.class);
        Method method = SampleService.class.getMethod("limitedMethod", String.class);

        when(joinPoint.getSignature()).thenReturn(signature);
        when(joinPoint.getArgs()).thenReturn(new Object[]{"order123"});
        when(joinPoint.proceed()).thenReturn("success");
        when(signature.getMethod()).thenReturn(method);
        when(signature.getDeclaringTypeName()).thenReturn(SampleService.class.getName());
        when(signature.getName()).thenReturn("limitedMethod");

        RateLimiter rateLimiter = method.getAnnotation(RateLimiter.class);

        // 第 1 次放行
        Object r1 = aspect.around(joinPoint, rateLimiter);
        assertThat(r1).isEqualTo("success");

        // 第 2 次放行（限流 2 次）
        Object r2 = aspect.around(joinPoint, rateLimiter);
        assertThat(r2).isEqualTo("success");

        // 第 3 次拦截抛出 429 异常，并检查 Retry-After 响应头
        assertThatThrownBy(() -> aspect.around(joinPoint, rateLimiter))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("访问过于频繁");

        assertThat(response.getHeader("Retry-After")).isEqualTo("60");
    }

    @Test
    @DisplayName("限流切面基于 USER 维度及 SpEL 表达式计算")
    void shouldEvaluateSpelAndUserDimension() throws Throwable {
        UserContextHolder.set("user_999");
        ProceedingJoinPoint joinPoint = mock(ProceedingJoinPoint.class);
        MethodSignature signature = mock(MethodSignature.class);
        Method method = SampleService.class.getMethod("userSpelMethod", String.class);

        when(joinPoint.getSignature()).thenReturn(signature);
        when(joinPoint.getArgs()).thenReturn(new Object[]{"paramA"});
        when(joinPoint.proceed()).thenReturn("ok");
        when(signature.getMethod()).thenReturn(method);
        when(signature.getParameterNames()).thenReturn(new String[]{"bizId"});

        RateLimiter rateLimiter = method.getAnnotation(RateLimiter.class);

        // 验证执行通过并复用 expressionCache
        Object result = aspect.around(joinPoint, rateLimiter);
        assertThat(result).isEqualTo("ok");
    }

    @Test
    @DisplayName("限流切面基于 TENANT 维度实现多租户流量隔离与超限熔断")
    void shouldEvaluateTenantDimensionAndIsolate() throws Throwable {
        ProceedingJoinPoint joinPoint = mock(ProceedingJoinPoint.class);
        MethodSignature signature = mock(MethodSignature.class);
        Method method = SampleService.class.getMethod("tenantLimitedMethod", String.class);

        when(joinPoint.getSignature()).thenReturn(signature);
        when(joinPoint.getArgs()).thenReturn(new Object[]{"DATA_A"});
        when(joinPoint.proceed()).thenReturn("tenant_ok");
        when(signature.getMethod()).thenReturn(method);
        when(signature.getDeclaringTypeName()).thenReturn(SampleService.class.getName());
        when(signature.getName()).thenReturn("tenantLimitedMethod");

        RateLimiter rateLimiter = method.getAnnotation(RateLimiter.class);

        // 1. 租户 1 执行 1 次放行
        TenantContextHolder.setTenantId("tenant_001");
        Object r1 = aspect.around(joinPoint, rateLimiter);
        assertThat(r1).isEqualTo("tenant_ok");

        // 租户 1 执行第 2 次触发超限 429
        assertThatThrownBy(() -> aspect.around(joinPoint, rateLimiter))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("多租户限流已触发");

        // 2. 租户 2 应该不受租户 1 限流影响（流量隔离）
        TenantContextHolder.setTenantId("tenant_002");
        Object r2 = aspect.around(joinPoint, rateLimiter);
        assertThat(r2).isEqualTo("tenant_ok");

        // 租户 2 执行第 2 次也触发超限
        assertThatThrownBy(() -> aspect.around(joinPoint, rateLimiter))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("多租户限流已触发");
    }

    @Test
    @DisplayName("限流切面在未设置租户时自动回退为 default 租户限流")
    void shouldFallbackToDefaultTenantWhenEmpty() throws Throwable {
        TenantContextHolder.clear();
        ProceedingJoinPoint joinPoint = mock(ProceedingJoinPoint.class);
        MethodSignature signature = mock(MethodSignature.class);
        Method method = SampleService.class.getMethod("tenantLimitedMethod", String.class);

        when(joinPoint.getSignature()).thenReturn(signature);
        when(joinPoint.getArgs()).thenReturn(new Object[]{"DATA_A"});
        when(joinPoint.proceed()).thenReturn("tenant_ok");
        when(signature.getMethod()).thenReturn(method);
        when(signature.getDeclaringTypeName()).thenReturn(SampleService.class.getName());
        when(signature.getName()).thenReturn("tenantLimitedMethod");

        RateLimiter rateLimiter = method.getAnnotation(RateLimiter.class);

        // 第 1 次放行
        Object r1 = aspect.around(joinPoint, rateLimiter);
        assertThat(r1).isEqualTo("tenant_ok");

        // 第 2 次超限
        assertThatThrownBy(() -> aspect.around(joinPoint, rateLimiter))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("验证切面优先级与 failOpen 容灾降级行为")
    void shouldVerifyOrderAndFailOpen() throws Throwable {
        assertThat(aspect.getOrder()).isEqualTo(RateLimiterAspect.ORDER);
        assertThat(RateLimiterAspect.ORDER).isEqualTo(org.springframework.core.Ordered.HIGHEST_PRECEDENCE + 200);

        RateLimiterStore brokenStore = mock(RateLimiterStore.class);
        when(brokenStore.tryAcquire(anyString(), anyLong(), anyLong(), any()))
                .thenThrow(new BusinessException(500, "Redis down"));

        ProceedingJoinPoint joinPoint = mock(ProceedingJoinPoint.class);
        MethodSignature signature = mock(MethodSignature.class);
        Method method = SampleService.class.getMethod("limitedMethod", String.class);
        when(joinPoint.getSignature()).thenReturn(signature);
        when(joinPoint.getArgs()).thenReturn(new Object[]{"order123"});
        when(joinPoint.proceed()).thenReturn("success");
        when(signature.getMethod()).thenReturn(method);
        when(signature.getDeclaringTypeName()).thenReturn(SampleService.class.getName());
        when(signature.getName()).thenReturn("limitedMethod");
        RateLimiter rateLimiter = method.getAnnotation(RateLimiter.class);

        // 1. failOpen = false (默认) -> 抛出异常
        io.github.atengk.boot.web.ratelimit.properties.RateLimiterProperties propsClosed =
                new io.github.atengk.boot.web.ratelimit.properties.RateLimiterProperties();
        propsClosed.setFailOpen(false);
        RateLimiterAspect aspectClosed = new RateLimiterAspect(brokenStore, propsClosed);
        assertThatThrownBy(() -> aspectClosed.around(joinPoint, rateLimiter))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Redis down");

        // 2. failOpen = true -> 降级至内存存储放行
        io.github.atengk.boot.web.ratelimit.properties.RateLimiterProperties propsOpen =
                new io.github.atengk.boot.web.ratelimit.properties.RateLimiterProperties();
        propsOpen.setFailOpen(true);
        RateLimiterAspect aspectOpen = new RateLimiterAspect(brokenStore, propsOpen);
        Object result = aspectOpen.around(joinPoint, rateLimiter);
        assertThat(result).isEqualTo("success");
    }

    @Test
    @DisplayName("IP 维度限流防伪造：直连公网来源伪造 X-Forwarded-For 时始终按真实 RemoteAddr 计入同一限流桶")
    void shouldIgnoreSpoofedForwardedForForIpRateLimit() throws Throwable {
        ProceedingJoinPoint joinPoint = mock(ProceedingJoinPoint.class);
        MethodSignature signature = mock(MethodSignature.class);
        Method method = SampleService.class.getMethod("ipLimitedMethod");
        when(joinPoint.getSignature()).thenReturn(signature);
        when(joinPoint.getArgs()).thenReturn(new Object[0]);
        when(joinPoint.proceed()).thenReturn("ip_ok");
        when(signature.getMethod()).thenReturn(method);
        when(signature.getDeclaringTypeName()).thenReturn(SampleService.class.getName());
        when(signature.getName()).thenReturn("ipLimitedMethod");
        RateLimiter rateLimiter = method.getAnnotation(RateLimiter.class);

        // 第 1 次请求：真实 RemoteAddr 为 203.0.113.77（非授信代理），伪造 X-Forwarded-For: 1.1.1.1
        MockHttpServletRequest req1 = new MockHttpServletRequest("GET", "/api/ip-test");
        req1.setRemoteAddr("203.0.113.77");
        req1.addHeader("X-Forwarded-For", "1.1.1.1");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(req1, new MockHttpServletResponse()));
        assertThat(aspect.around(joinPoint, rateLimiter)).isEqualTo("ip_ok");

        // 第 2 次请求：同一攻击者篡改 X-Forwarded-For: 2.2.2.2，仍应命中 203.0.113.77 限流桶并被拦截
        MockHttpServletRequest req2 = new MockHttpServletRequest("GET", "/api/ip-test");
        req2.setRemoteAddr("203.0.113.77");
        req2.addHeader("X-Forwarded-For", "2.2.2.2");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(req2, new MockHttpServletResponse()));
        assertThatThrownBy(() -> aspect.around(joinPoint, rateLimiter))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("验证全局 keyPrefix 配置生效及注解显式 prefix 优先级覆盖")
    void shouldRespectGlobalKeyPrefixAndAnnotationOverride() throws Throwable {
        RateLimiterStore spyStore = org.mockito.Mockito.spy(store);
        io.github.atengk.boot.web.ratelimit.properties.RateLimiterProperties props =
                new io.github.atengk.boot.web.ratelimit.properties.RateLimiterProperties();
        props.setKeyPrefix("gw:rl:");
        RateLimiterAspect customAspect = new RateLimiterAspect(spyStore, props);

        ProceedingJoinPoint joinPoint = mock(ProceedingJoinPoint.class);
        MethodSignature signature = mock(MethodSignature.class);
        when(joinPoint.getSignature()).thenReturn(signature);
        when(joinPoint.getArgs()).thenReturn(new Object[]{"order123"});
        when(joinPoint.proceed()).thenReturn("success");
        when(signature.getDeclaringTypeName()).thenReturn(SampleService.class.getName());

        // 1. 注解保持默认 prefix 时，生效全局 keyPrefix ("gw:rl:")
        Method defaultMethod = SampleService.class.getMethod("limitedMethod", String.class);
        when(signature.getMethod()).thenReturn(defaultMethod);
        when(signature.getName()).thenReturn("limitedMethod");
        customAspect.around(joinPoint, defaultMethod.getAnnotation(RateLimiter.class));
        org.mockito.Mockito.verify(spyStore).tryAcquire(
                org.mockito.ArgumentMatchers.startsWith("gw:rl:default:"),
                anyLong(), anyLong(), any());

        // 2. 注解显式指定自定义 prefix 时，覆盖全局 keyPrefix
        Method customMethod = SampleService.class.getMethod("customPrefixMethod");
        when(signature.getMethod()).thenReturn(customMethod);
        when(signature.getName()).thenReturn("customPrefixMethod");
        when(joinPoint.getArgs()).thenReturn(new Object[0]);
        customAspect.around(joinPoint, customMethod.getAnnotation(RateLimiter.class));
        org.mockito.Mockito.verify(spyStore).tryAcquire(
                org.mockito.ArgumentMatchers.startsWith("custom_rl:default:"),
                anyLong(), anyLong(), any());
    }

    static class SampleService {
        @RateLimiter(count = 2, time = 60, timeUnit = TimeUnit.SECONDS, message = "访问过于频繁，请稍后再试")
        public String limitedMethod(String orderId) {
            return "success";
        }

        @RateLimiter(limitType = LimitType.USER, key = "#bizId", count = 5, time = 10, timeUnit = TimeUnit.SECONDS)
        public String userSpelMethod(String bizId) {
            return "ok";
        }

        @RateLimiter(limitType = LimitType.TENANT, count = 1, time = 60, timeUnit = TimeUnit.SECONDS, message = "多租户限流已触发")
        public String tenantLimitedMethod(String data) {
            return "tenant_ok";
        }

        @RateLimiter(limitType = LimitType.IP, count = 1, time = 60, timeUnit = TimeUnit.SECONDS)
        public String ipLimitedMethod() {
            return "ip_ok";
        }

        @RateLimiter(prefix = "custom_rl:", count = 2, time = 60, timeUnit = TimeUnit.SECONDS)
        public String customPrefixMethod() {
            return "custom_ok";
        }
    }
}
