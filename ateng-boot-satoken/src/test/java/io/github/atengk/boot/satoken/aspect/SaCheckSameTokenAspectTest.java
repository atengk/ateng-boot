package io.github.atengk.boot.satoken.aspect;

import cn.dev33.satoken.exception.SameTokenInvalidException;
import cn.dev33.satoken.same.SaSameUtil;
import io.github.atengk.boot.satoken.annotation.CheckSameToken;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * SaCheckSameTokenAspect 微服务内部 Same-Token 零信任切面单元测试
 *
 * @author Ateng
 * @since 2026-10-17
 */
@DisplayName("SaCheckSameTokenAspect 零信任切面单元测试")
class SaCheckSameTokenAspectTest {

    interface TestService {
        String callInternal();
    }

    static class TestServiceImpl implements TestService {
        @Override
        @CheckSameToken
        public String callInternal() {
            return "SUCCESS_INTERNAL";
        }
    }

    private cn.dev33.satoken.context.SaTokenContext originalContext;
    private cn.dev33.satoken.context.SaTokenContext mockContext;
    private cn.dev33.satoken.context.model.SaRequest mockRequest;

    @BeforeEach
    void setUp() {
        originalContext = cn.dev33.satoken.SaManager.getSaTokenContext();
        mockContext = mock(cn.dev33.satoken.context.SaTokenContext.class);
        mockRequest = mock(cn.dev33.satoken.context.model.SaRequest.class);
        when(mockContext.getRequest()).thenReturn(mockRequest);
        when(mockContext.isValid()).thenReturn(true);
        cn.dev33.satoken.SaManager.setSaTokenContext(mockContext);
    }

    @AfterEach
    void tearDown() {
        cn.dev33.satoken.SaManager.setSaTokenContext(originalContext);
    }

    @Test
    @DisplayName("测试未携带有效 Same-Token 时拦截抛出 SameTokenInvalidException")
    void testInterceptWithoutSameToken() {
        when(mockRequest.getHeader(SaSameUtil.SAME_TOKEN)).thenReturn(null);

        TestServiceImpl target = new TestServiceImpl();
        AspectJProxyFactory factory = new AspectJProxyFactory(target);
        factory.addAspect(new SaCheckSameTokenAspect());
        TestService proxy = factory.getProxy();

        assertThatThrownBy(proxy::callInternal)
                .isInstanceOf(SameTokenInvalidException.class);
    }

    @Test
    @DisplayName("测试携带合法 Same-Token 时校验通过并成功执行方法")
    void testPassWithValidSameToken() {
        String validSameToken = SaSameUtil.getToken();
        when(mockRequest.getHeader(SaSameUtil.SAME_TOKEN)).thenReturn(validSameToken);

        TestServiceImpl target = new TestServiceImpl();
        AspectJProxyFactory factory = new AspectJProxyFactory(target);
        factory.addAspect(new SaCheckSameTokenAspect());
        TestService proxy = factory.getProxy();

        String result = proxy.callInternal();
        assertThat(result).isEqualTo("SUCCESS_INTERNAL");
    }

    @Test
    @DisplayName("测试自定义 Same-Token 请求头配置校验通过")
    void testPassWithCustomHeader() {
        io.github.atengk.boot.satoken.properties.SaTokenProperties properties = new io.github.atengk.boot.satoken.properties.SaTokenProperties();
        properties.getSameToken().setHeaderName("X-Custom-Same-Token");

        String validSameToken = SaSameUtil.getToken();
        when(mockRequest.getHeader("X-Custom-Same-Token")).thenReturn(validSameToken);

        TestServiceImpl target = new TestServiceImpl();
        AspectJProxyFactory factory = new AspectJProxyFactory(target);
        factory.addAspect(new SaCheckSameTokenAspect(properties));
        TestService proxy = factory.getProxy();

        String result = proxy.callInternal();
        assertThat(result).isEqualTo("SUCCESS_INTERNAL");
    }
}
