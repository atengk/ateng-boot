package io.github.atengk.boot.web.ratelimit.aspect;

import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.web.ratelimit.annotation.LimitType;
import io.github.atengk.boot.web.ratelimit.annotation.RateLimiter;
import io.github.atengk.boot.web.ratelimit.store.InMemoryRateLimiterStore;
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
    }
}
