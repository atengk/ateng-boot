package io.github.atengk.boot.redis.idempotent;

import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.core.exception.IdempotentException;
import io.github.atengk.boot.redis.idempotent.annotation.RedisIdempotent;
import io.github.atengk.boot.redis.idempotent.aspect.RedisIdempotentAspect;
import io.github.atengk.boot.redis.properties.RedisProperties;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;

import java.lang.reflect.Method;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * RedisIdempotentAspect 单元测试
 *
 * @author Ateng
 * @since 2026-10-27
 */
@DisplayName("RedisIdempotentAspect 单元测试")
class RedisIdempotentAspectTest {

    private final RedissonClient redissonClient = mock(RedissonClient.class);
    @SuppressWarnings("unchecked")
    private final RBucket<String> bucket = mock(RBucket.class);

    private RedisIdempotentTemplate template;
    private RedisIdempotentAspect aspect;

    @BeforeEach
    void setUp() {
        doReturn(bucket).when(redissonClient).getBucket(any(), eq(StringCodec.INSTANCE));
        RedisProperties properties = new RedisProperties();
        template = new RedisIdempotentTemplate(redissonClient, properties);
        aspect = new RedisIdempotentAspect(template, properties);
    }

    static class SampleService {
        @RedisIdempotent(key = "#orderId", expire = "5m", message = "订单处理中，请勿重复操作")
        public String createOrder(String orderId) {
            return "created:" + orderId;
        }

        @RedisIdempotent(key = "static:lock:key", expire = "10s")
        public String staticKeyMethod() {
            return "static_ok";
        }

        @RedisIdempotent(expire = "1m")
        public String defaultSignatureMethod() {
            return "sig_ok";
        }

        @RedisIdempotent(key = "#emptyVal")
        public String emptySpelMethod(String emptyVal) {
            return "should_fail";
        }
    }

    @Test
    @DisplayName("测试 SpEL 动态业务键解析与防重切面正常放行")
    void testSpelKeyResolutionSuccess() throws Throwable {
        when(bucket.setIfAbsent(eq("TRYING"), any())).thenReturn(true);

        Method method = SampleService.class.getMethod("createOrder", String.class);
        RedisIdempotent annotation = method.getAnnotation(RedisIdempotent.class);

        ProceedingJoinPoint joinPoint = mock(ProceedingJoinPoint.class);
        MethodSignature signature = mock(MethodSignature.class);
        when(signature.getMethod()).thenReturn(method);
        when(signature.getParameterNames()).thenReturn(new String[]{"orderId"});
        when(joinPoint.getSignature()).thenReturn(signature);
        when(joinPoint.getArgs()).thenReturn(new Object[]{"ORD_20261027_001"});
        when(joinPoint.proceed()).thenReturn("created:ORD_20261027_001");

        Object result = aspect.around(joinPoint, annotation);

        assertThat(result).isEqualTo("created:ORD_20261027_001");
        verify(bucket).setIfAbsent(eq("TRYING"), eq(Duration.ofMinutes(5)));
        verify(bucket).set(eq("SUCCESS"), eq(Duration.ofMinutes(5)));
    }

    @Test
    @DisplayName("测试 SpEL 业务键解析并发重复拦截")
    void testSpelKeyDuplicateBlocked() throws Throwable {
        when(bucket.setIfAbsent(eq("TRYING"), any())).thenReturn(false);

        Method method = SampleService.class.getMethod("createOrder", String.class);
        RedisIdempotent annotation = method.getAnnotation(RedisIdempotent.class);

        ProceedingJoinPoint joinPoint = mock(ProceedingJoinPoint.class);
        MethodSignature signature = mock(MethodSignature.class);
        when(signature.getMethod()).thenReturn(method);
        when(signature.getParameterNames()).thenReturn(new String[]{"orderId"});
        when(joinPoint.getSignature()).thenReturn(signature);
        when(joinPoint.getArgs()).thenReturn(new Object[]{"ORD_DUP"});

        assertThatThrownBy(() -> aspect.around(joinPoint, annotation))
                .isInstanceOf(IdempotentException.class)
                .hasMessage("订单处理中，请勿重复操作");
    }

    @Test
    @DisplayName("测试静态 Key 解析与切面放行")
    void testStaticKey() throws Throwable {
        when(bucket.setIfAbsent(eq("TRYING"), any())).thenReturn(true);

        Method method = SampleService.class.getMethod("staticKeyMethod");
        RedisIdempotent annotation = method.getAnnotation(RedisIdempotent.class);

        ProceedingJoinPoint joinPoint = mock(ProceedingJoinPoint.class);
        MethodSignature signature = mock(MethodSignature.class);
        when(signature.getMethod()).thenReturn(method);
        when(joinPoint.getSignature()).thenReturn(signature);
        when(joinPoint.proceed()).thenReturn("static_ok");

        Object result = aspect.around(joinPoint, annotation);

        assertThat(result).isEqualTo("static_ok");
        verify(bucket).setIfAbsent(eq("TRYING"), eq(Duration.ofSeconds(10)));
    }

    @Test
    @DisplayName("测试留空 key 时降级采用方法全限定签名兜底")
    void testDefaultMethodSignatureKey() throws Throwable {
        when(bucket.setIfAbsent(eq("TRYING"), any())).thenReturn(true);

        Method method = SampleService.class.getMethod("defaultSignatureMethod");
        RedisIdempotent annotation = method.getAnnotation(RedisIdempotent.class);

        ProceedingJoinPoint joinPoint = mock(ProceedingJoinPoint.class);
        MethodSignature signature = mock(MethodSignature.class);
        when(signature.getMethod()).thenReturn(method);
        when(joinPoint.getSignature()).thenReturn(signature);
        when(joinPoint.proceed()).thenReturn("sig_ok");

        Object result = aspect.around(joinPoint, annotation);

        assertThat(result).isEqualTo("sig_ok");
    }

    @Test
    @DisplayName("测试 SpEL 计算结果为空时抛出 BAD_REQUEST 业务异常")
    void testSpelNullResultThrowsBusinessException() throws Throwable {
        Method method = SampleService.class.getMethod("emptySpelMethod", String.class);
        RedisIdempotent annotation = method.getAnnotation(RedisIdempotent.class);

        ProceedingJoinPoint joinPoint = mock(ProceedingJoinPoint.class);
        MethodSignature signature = mock(MethodSignature.class);
        when(signature.getMethod()).thenReturn(method);
        when(signature.getParameterNames()).thenReturn(new String[]{"emptyVal"});
        when(joinPoint.getSignature()).thenReturn(signature);
        when(joinPoint.getArgs()).thenReturn(new Object[]{null});

        assertThatThrownBy(() -> aspect.around(joinPoint, annotation))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getCode())
                .isEqualTo(CommonResultCode.BAD_REQUEST.getCode());
    }

    @Test
    @DisplayName("测试 Duration 时长表达式稳健解析")
    void testDurationParsing() {
        assertThat(RedisIdempotentAspect.parseExpire("10m")).isEqualTo(Duration.ofMinutes(10));
        assertThat(RedisIdempotentAspect.parseExpire("30s")).isEqualTo(Duration.ofSeconds(30));
        assertThat(RedisIdempotentAspect.parseExpire("1h")).isEqualTo(Duration.ofHours(1));
        assertThat(RedisIdempotentAspect.parseExpire("100ms")).isEqualTo(Duration.ofMillis(100));
        assertThat(RedisIdempotentAspect.parseExpire("60")).isEqualTo(Duration.ofSeconds(60));
        assertThat(RedisIdempotentAspect.parseExpire("")).isNull();
        assertThat(RedisIdempotentAspect.parseExpire(null)).isNull();

        assertThatThrownBy(() -> RedisIdempotentAspect.parseExpire("invalid_style"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("无法解析 @RedisIdempotent expire 时长表达式");
    }
}
