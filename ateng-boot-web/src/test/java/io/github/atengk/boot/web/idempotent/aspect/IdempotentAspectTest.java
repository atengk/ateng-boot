package io.github.atengk.boot.web.idempotent.aspect;

import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.web.idempotent.annotation.Idempotent;
import io.github.atengk.boot.web.idempotent.properties.IdempotentProperties;
import io.github.atengk.boot.web.idempotent.store.IdempotentKeyStore;
import io.github.atengk.boot.web.idempotent.store.InMemoryIdempotentKeyStore;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.Ordered;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.lang.reflect.Method;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * 接口防重幂等 AOP 切面单元测试
 *
 * @author Ateng
 * @since 2026-11-09
 */
@DisplayName("防重幂等 AOP 切面单元测试")
class IdempotentAspectTest {

    private InMemoryIdempotentKeyStore store;
    private IdempotentProperties properties;
    private IdempotentAspect aspect;
    private MockHttpServletRequest request;
    private MockHttpServletResponse response;

    @BeforeEach
    void setUp() {
        store = new InMemoryIdempotentKeyStore();
        properties = new IdempotentProperties();
        aspect = new IdempotentAspect(store, properties);
        request = new MockHttpServletRequest("POST", "/api/order/submit");
        response = new MockHttpServletResponse();
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request, response));
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
        store.destroy();
    }

    @Test
    @DisplayName("验证切面优先级契约")
    void shouldVerifyAspectOrder() {
        assertThat(aspect.getOrder()).isEqualTo(IdempotentAspect.ORDER);
        assertThat(IdempotentAspect.ORDER).isEqualTo(Ordered.HIGHEST_PRECEDENCE + 300);
    }

    @Test
    @DisplayName("首次请求成功放行，重复请求抛出 REPEAT_SUBMIT 异常")
    void shouldAcquireFirstAndBlockDuplicate() throws Throwable {
        ProceedingJoinPoint joinPoint = mock(ProceedingJoinPoint.class);
        MethodSignature signature = mock(MethodSignature.class);
        Method method = SampleOrderService.class.getMethod("submitOrder", String.class);

        when(joinPoint.getSignature()).thenReturn(signature);
        when(joinPoint.getArgs()).thenReturn(new Object[]{"ORD_1001"});
        when(joinPoint.proceed()).thenReturn("order_success");
        when(signature.getMethod()).thenReturn(method);
        when(signature.getParameterNames()).thenReturn(new String[]{"orderNo"});

        Idempotent idempotent = method.getAnnotation(Idempotent.class);

        Object result1 = aspect.around(joinPoint, idempotent);
        assertThat(result1).isEqualTo("order_success");

        // 重复调用应被拦截
        assertThatThrownBy(() -> aspect.around(joinPoint, idempotent))
                .isInstanceOf(BusinessException.class)
                .extracting("code")
                .isEqualTo(CommonResultCode.REPEAT_SUBMIT.getCode());
    }

    @Test
    @DisplayName("底层存储故障时 failOpen=false 抛出异常，failOpen=true 降级至本地内存存储")
    void shouldHandleFailOpenResilience() throws Throwable {
        IdempotentKeyStore brokenStore = mock(IdempotentKeyStore.class);
        when(brokenStore.tryLock(anyString(), anyLong(), any()))
                .thenThrow(new BusinessException(CommonResultCode.INTERNAL_SERVER_ERROR.getCode(), "Redis timeout"));

        ProceedingJoinPoint joinPoint = mock(ProceedingJoinPoint.class);
        MethodSignature signature = mock(MethodSignature.class);
        Method method = SampleOrderService.class.getMethod("submitOrder", String.class);

        when(joinPoint.getSignature()).thenReturn(signature);
        when(joinPoint.getArgs()).thenReturn(new Object[]{"ORD_FAILOPEN"});
        when(joinPoint.proceed()).thenReturn("fallback_success");
        when(signature.getMethod()).thenReturn(method);
        when(signature.getParameterNames()).thenReturn(new String[]{"orderNo"});

        Idempotent idempotent = method.getAnnotation(Idempotent.class);

        // 1. failOpen = false
        IdempotentProperties propsClosed = new IdempotentProperties();
        propsClosed.setFailOpen(false);
        IdempotentAspect aspectClosed = new IdempotentAspect(brokenStore, propsClosed);
        assertThatThrownBy(() -> aspectClosed.around(joinPoint, idempotent))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Redis timeout");

        // 2. failOpen = true
        IdempotentProperties propsOpen = new IdempotentProperties();
        propsOpen.setFailOpen(true);
        IdempotentAspect aspectOpen = new IdempotentAspect(brokenStore, propsOpen);
        Object result = aspectOpen.around(joinPoint, idempotent);
        assertThat(result).isEqualTo("fallback_success");
    }

    static class SampleOrderService {
        @Idempotent(key = "#orderNo", timeout = 10, timeUnit = TimeUnit.SECONDS)
        public String submitOrder(String orderNo) {
            return "order_success";
        }
    }
}
