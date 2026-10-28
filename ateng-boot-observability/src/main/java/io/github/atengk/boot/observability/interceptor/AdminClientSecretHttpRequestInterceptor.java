package io.github.atengk.boot.observability.interceptor;

import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.util.StringUtils;

import java.io.IOException;

/**
 * Spring Boot Admin 客户端握手通信密钥拦截器
 * 自动向客户端发往服务端的 HTTP 请求头附加 X-Admin-Client-Secret
 *
 * @author Ateng
 * @since 2026-10-26
 */
public class AdminClientSecretHttpRequestInterceptor implements ClientHttpRequestInterceptor {

    public static final String CLIENT_SECRET_HEADER = "X-Admin-Client-Secret";

    private final String clientSecret;

    public AdminClientSecretHttpRequestInterceptor(String clientSecret) {
        this.clientSecret = clientSecret;
    }

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
            throws IOException {
        if (StringUtils.hasText(clientSecret) && !request.getHeaders().containsKey(CLIENT_SECRET_HEADER)) {
            request.getHeaders().add(CLIENT_SECRET_HEADER, clientSecret);
        }
        return execution.execute(request, body);
    }

    public String getClientSecret() {
        return clientSecret;
    }
}
