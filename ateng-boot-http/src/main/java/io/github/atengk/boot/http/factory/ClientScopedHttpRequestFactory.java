package io.github.atengk.boot.http.factory;

import io.github.atengk.boot.http.context.HttpRequestContextHolder;
import io.github.atengk.boot.http.options.RequestOptionsApplier;
import lombok.Getter;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.ClientHttpRequest;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.util.Assert;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;

/**
 * 命名客户端专属请求工厂包装器
 * 共享底层物理连接池工厂，为指定命名客户端动态附加专属的出站超时（readTimeout、connectTimeout）控制
 *
 * @author Ateng
 * @since 2026-10-21
 */
@Getter
public class ClientScopedHttpRequestFactory implements ClientHttpRequestFactory {

    private final ClientHttpRequestFactory delegate;
    private final Duration readTimeout;
    private final Duration connectTimeout;

    public ClientScopedHttpRequestFactory(ClientHttpRequestFactory delegate, Duration readTimeout, Duration connectTimeout) {
        Assert.notNull(delegate, "delegate ClientHttpRequestFactory must not be null");
        this.delegate = delegate;
        this.readTimeout = readTimeout;
        this.connectTimeout = connectTimeout;
    }

    @Override
    public ClientHttpRequest createRequest(URI uri, HttpMethod httpMethod) throws IOException {
        Duration previousTimeout = HttpRequestContextHolder.getReadTimeout();
        // 仅在当前未绑定单次特定超时时，应用客户端专属读取超时作为当前线程上下文默认值
        if (this.readTimeout != null && previousTimeout == null) {
            HttpRequestContextHolder.setReadTimeout(this.readTimeout);
        }
        try {
            ClientHttpRequest request = this.delegate.createRequest(uri, httpMethod);
            // 将客户端专属的 readTimeout 与 connectTimeout 精准赋能至物理请求对象
            RequestOptionsApplier.applyTimeout(request, this.readTimeout, this.connectTimeout);
            return request;
        } finally {
            // 恪守上下文对称清理与现场还原铁律，恢复原有上下文状态，严防线程复用污染
            if (this.readTimeout != null && previousTimeout == null) {
                HttpRequestContextHolder.clear();
            } else {
                HttpRequestContextHolder.setReadTimeout(previousTimeout);
            }
        }
    }
}
