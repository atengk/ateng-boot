package io.github.atengk.boot.web.ip.aspect;

import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.web.ip.IpAccessMode;
import io.github.atengk.boot.web.ip.annotation.IpAccessControl;
import io.github.atengk.boot.web.ip.filter.IpAccessControlFilter;
import io.github.atengk.boot.web.ip.properties.IpAccessControlProperties;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 声明式 @IpAccessControl AOP 拦截切面单测
 *
 * @author Ateng
 * @since 2026-10-27
 */
@DisplayName("声明式 @IpAccessControl AOP 拦截切面单测")
class IpAccessControlAspectTest {

    private IpAccessControlProperties properties;
    private IpAccessControlAspect aspect;
    private MockHttpServletRequest request;
    private MockHttpServletResponse response;

    @BeforeEach
    void setUp() {
        properties = new IpAccessControlProperties();
        properties.setEnabled(true);
        aspect = new IpAccessControlAspect(properties);
        request = new MockHttpServletRequest("GET", "/api/test");
        response = new MockHttpServletResponse();
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request, response));
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    @DisplayName("白名单模式：规则内 IP 正常放行，规则外 IP 抛出 403 业务异常并写入 X-Denied-Reason 标头")
    void shouldHandleWhiteListMethod() throws Throwable {
        ProceedingJoinPoint joinPoint = mock(ProceedingJoinPoint.class);
        MethodSignature signature = mock(MethodSignature.class);
        Method method = SampleService.class.getMethod("whiteListMethod");

        when(joinPoint.getSignature()).thenReturn(signature);
        when(joinPoint.proceed()).thenReturn("success");
        when(joinPoint.getTarget()).thenReturn(new SampleService());
        when(signature.getMethod()).thenReturn(method);
        when(signature.toShortString()).thenReturn(method.getName());

        // 1. 白名单内 IP 放行
        request.setRemoteAddr("192.168.1.50");
        Object result = aspect.around(joinPoint);
        assertThat(result).isEqualTo("success");

        // 2. 白名单外 IP 拦截
        request.setRemoteAddr("10.0.0.1");
        assertThatThrownBy(() -> aspect.around(joinPoint))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> {
                    BusinessException be = (BusinessException) e;
                    assertThat(be.getCode()).isEqualTo(403);
                    assertThat(be.getMsg()).isEqualTo("IP 访问受限，拒绝访问");
                });

        assertThat(response.getHeader(IpAccessControlFilter.DENIED_HEADER)).isEqualTo(IpAccessControlFilter.DENIED_REASON);
    }

    @Test
    @DisplayName("黑名单模式：黑名单内 IP 阻断，其他放行")
    void shouldHandleBlackListMethod() throws Throwable {
        ProceedingJoinPoint joinPoint = mock(ProceedingJoinPoint.class);
        MethodSignature signature = mock(MethodSignature.class);
        Method method = SampleService.class.getMethod("blackListMethod");

        when(joinPoint.getSignature()).thenReturn(signature);
        when(joinPoint.proceed()).thenReturn("success");
        when(joinPoint.getTarget()).thenReturn(new SampleService());
        when(signature.getMethod()).thenReturn(method);
        when(signature.toShortString()).thenReturn(method.getName());

        // 1. 黑名单内 IP 阻断
        request.setRemoteAddr("10.1.2.3");
        assertThatThrownBy(() -> aspect.around(joinPoint))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo(403));

        assertThat(response.getHeader(IpAccessControlFilter.DENIED_HEADER)).isEqualTo(IpAccessControlFilter.DENIED_REASON);

        // 2. 正常 IP 放行
        request.setRemoteAddr("192.168.1.1");
        Object result = aspect.around(joinPoint);
        assertThat(result).isEqualTo("success");
    }

    @Test
    @DisplayName("自定义拒绝文案与物理 403 状态码测试")
    void shouldHandleCustomMessageAndHttpStatus403() throws Throwable {
        ProceedingJoinPoint joinPoint = mock(ProceedingJoinPoint.class);
        MethodSignature signature = mock(MethodSignature.class);
        Method method = SampleService.class.getMethod("customMessageAndStatus403Method");

        when(joinPoint.getSignature()).thenReturn(signature);
        when(joinPoint.proceed()).thenReturn("success");
        when(joinPoint.getTarget()).thenReturn(new SampleService());
        when(signature.getMethod()).thenReturn(method);
        when(signature.toShortString()).thenReturn(method.getName());

        request.setRemoteAddr("10.0.0.1"); // 非 127.0.0.1
        assertThatThrownBy(() -> aspect.around(joinPoint))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> {
                    BusinessException be = (BusinessException) e;
                    assertThat(be.getCode()).isEqualTo(403);
                    assertThat(be.getMsg()).isEqualTo("敏感内部管理接口，非特许 IP 禁止访问");
                });

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getHeader(IpAccessControlFilter.DENIED_HEADER)).isEqualTo(IpAccessControlFilter.DENIED_REASON);
    }

    @Test
    @DisplayName("类级别注解继承与方法级注解覆盖测试")
    void shouldSupportClassLevelAnnotationAndMethodOverride() throws Throwable {
        ProceedingJoinPoint joinPoint1 = mock(ProceedingJoinPoint.class);
        MethodSignature signature1 = mock(MethodSignature.class);
        Method method1 = AnnotatedClassService.class.getMethod("inheritedMethod");

        when(joinPoint1.getSignature()).thenReturn(signature1);
        when(joinPoint1.proceed()).thenReturn("inherited-ok");
        when(joinPoint1.getTarget()).thenReturn(new AnnotatedClassService());
        when(signature1.getMethod()).thenReturn(method1);

        // 1. 继承类级别白名单 192.168.1.0/24
        request.setRemoteAddr("192.168.1.20");
        assertThat(aspect.around(joinPoint1)).isEqualTo("inherited-ok");

        request.setRemoteAddr("10.0.0.1");
        assertThatThrownBy(() -> aspect.around(joinPoint1))
                .isInstanceOf(BusinessException.class);

        // 2. 方法级覆盖类级别规则（方法单独指定 10.0.0.0/8 白名单）
        ProceedingJoinPoint joinPoint2 = mock(ProceedingJoinPoint.class);
        MethodSignature signature2 = mock(MethodSignature.class);
        Method method2 = AnnotatedClassService.class.getMethod("overriddenMethod");

        when(joinPoint2.getSignature()).thenReturn(signature2);
        when(joinPoint2.proceed()).thenReturn("override-ok");
        when(joinPoint2.getTarget()).thenReturn(new AnnotatedClassService());
        when(signature2.getMethod()).thenReturn(method2);
        when(signature2.toShortString()).thenReturn(method2.getName());

        request.setRemoteAddr("10.0.0.5");
        assertThat(aspect.around(joinPoint2)).isEqualTo("override-ok");

        request.setRemoteAddr("192.168.1.20");
        assertThatThrownBy(() -> aspect.around(joinPoint2))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("当全局配置禁用时直接放行")
    void shouldPassWhenGlobalDisabled() throws Throwable {
        properties.setEnabled(false);

        ProceedingJoinPoint joinPoint = mock(ProceedingJoinPoint.class);
        MethodSignature signature = mock(MethodSignature.class);
        Method method = SampleService.class.getMethod("whiteListMethod");

        when(joinPoint.getSignature()).thenReturn(signature);
        when(joinPoint.proceed()).thenReturn("success");
        when(joinPoint.getTarget()).thenReturn(new SampleService());
        when(signature.getMethod()).thenReturn(method);
        when(signature.toShortString()).thenReturn(method.getName());

        request.setRemoteAddr("10.0.0.1"); // 未授权 IP
        Object result = aspect.around(joinPoint);
        assertThat(result).isEqualTo("success");
    }

    static class SampleService {

        @IpAccessControl(mode = IpAccessMode.WHITE_LIST, rules = {"192.168.1.0/24", "127.0.0.1"})
        public String whiteListMethod() {
            return "success";
        }

        @IpAccessControl(mode = IpAccessMode.BLACK_LIST, rules = {"10.0.0.0/8"})
        public String blackListMethod() {
            return "success";
        }

        @IpAccessControl(
                mode = IpAccessMode.WHITE_LIST,
                rules = {"127.0.0.1"},
                message = "敏感内部管理接口，非特许 IP 禁止访问",
                httpStatus403 = true
        )
        public String customMessageAndStatus403Method() {
            return "success";
        }
    }

    @IpAccessControl(mode = IpAccessMode.WHITE_LIST, rules = {"192.168.1.0/24"})
    static class AnnotatedClassService {

        public String inheritedMethod() {
            return "inherited-ok";
        }

        @IpAccessControl(mode = IpAccessMode.WHITE_LIST, rules = {"10.0.0.0/8"})
        public String overriddenMethod() {
            return "override-ok";
        }
    }
}
