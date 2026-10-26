package io.github.atengk.boot.observability.interceptor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.mock.http.client.MockClientHttpRequest;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * AdminClientSecretHttpRequestInterceptor 单元测试
 *
 * @author Ateng
 * @since 2026-10-26
 */
@DisplayName("AdminClientSecretHttpRequestInterceptor 单元测试")
class AdminClientSecretHttpRequestInterceptorTest {

    @Test
    @DisplayName("当配置了有效 ClientSecret 时，自动向请求头注入 X-Admin-Client-Secret")
    void testInjectSecretHeader() throws IOException {
        String secret = "test-secret-123456";
        AdminClientSecretHttpRequestInterceptor interceptor = new AdminClientSecretHttpRequestInterceptor(secret);

        MockClientHttpRequest request = new MockClientHttpRequest();
        ClientHttpRequestExecution execution = mock(ClientHttpRequestExecution.class);
        ClientHttpResponse mockResponse = mock(ClientHttpResponse.class);
        when(execution.execute(any(), any())).thenReturn(mockResponse);

        ClientHttpResponse actualResponse = interceptor.intercept(request, new byte[0], execution);

        assertThat(actualResponse).isSameAs(mockResponse);
        assertThat(request.getHeaders().getFirst(AdminClientSecretHttpRequestInterceptor.CLIENT_SECRET_HEADER))
                .isEqualTo(secret);
        verify(execution, times(1)).execute(any(), any());
    }

    @Test
    @DisplayName("当请求头已存在 X-Admin-Client-Secret 时，不覆盖已有值")
    void testDoNotOverwriteExistingHeader() throws IOException {
        String secret = "new-secret";
        AdminClientSecretHttpRequestInterceptor interceptor = new AdminClientSecretHttpRequestInterceptor(secret);

        MockClientHttpRequest request = new MockClientHttpRequest();
        request.getHeaders().add(AdminClientSecretHttpRequestInterceptor.CLIENT_SECRET_HEADER, "existing-secret");

        ClientHttpRequestExecution execution = mock(ClientHttpRequestExecution.class);
        when(execution.execute(any(), any())).thenReturn(mock(ClientHttpResponse.class));

        interceptor.intercept(request, new byte[0], execution);

        assertThat(request.getHeaders().getFirst(AdminClientSecretHttpRequestInterceptor.CLIENT_SECRET_HEADER))
                .isEqualTo("existing-secret");
    }

    @Test
    @DisplayName("当 ClientSecret 为空或空白字符串时，不附加请求头")
    void testEmptySecretDoesNotAddHeader() throws IOException {
        AdminClientSecretHttpRequestInterceptor interceptor = new AdminClientSecretHttpRequestInterceptor("");

        MockClientHttpRequest request = new MockClientHttpRequest();
        ClientHttpRequestExecution execution = mock(ClientHttpRequestExecution.class);
        when(execution.execute(any(), any())).thenReturn(mock(ClientHttpResponse.class));

        interceptor.intercept(request, new byte[0], execution);

        assertThat(request.getHeaders().containsKey(AdminClientSecretHttpRequestInterceptor.CLIENT_SECRET_HEADER)).isFalse();
    }
}
