package io.github.atengk.boot.satoken.interceptor;

import cn.dev33.satoken.same.SaSameUtil;
import io.github.atengk.boot.satoken.properties.SaTokenProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

import java.io.IOException;

/**
 * 微服务内部零信任调用客户端请求拦截器
 * <p>
 * 基于 Spring 6 原生 {@link ClientHttpRequestInterceptor} 契约接口实现。
 * 在微服务发起跨服务 HTTP 调用时，自动调用底层 {@link SaSameUtil#getToken()} 提取当前微服务内网防伪凭据，
 * 并安全注入至出站请求头（默认为 {@code SA-SAME-TOKEN}），与服务端 {@code @CheckSameToken} 切面构成全闭环防伪壁垒。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-28
 */
@Slf4j
public class SameTokenClientHttpRequestInterceptor implements ClientHttpRequestInterceptor {

    /**
     * 默认 Same-Token 请求头名称
     */
    public static final String DEFAULT_HEADER_NAME = "SA-SAME-TOKEN";

    private final SaTokenProperties properties;
    private final String headerName;

    public SameTokenClientHttpRequestInterceptor() {
        this((SaTokenProperties) null);
    }

    public SameTokenClientHttpRequestInterceptor(String headerName) {
        this.properties = null;
        this.headerName = headerName;
    }

    public SameTokenClientHttpRequestInterceptor(SaTokenProperties properties) {
        this.properties = properties;
        this.headerName = null;
    }

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution) throws IOException {
        if (properties != null && properties.getSameToken() != null && !properties.getSameToken().isEnabled()) {
            return execution.execute(request, body);
        }

        String header = resolveHeaderName();
        if (header != null && !header.isBlank() && !request.getHeaders().containsKey(header)) {
            try {
                String sameToken = SaSameUtil.getToken();
                if (sameToken != null && !sameToken.isBlank()) {
                    request.getHeaders().set(header, sameToken);
                    log.debug("微服务出站请求已成功注入 Same-Token 凭据 [header={}]", header);
                }
            } catch (Throwable ex) {
                log.warn("提取微服务内部 Same-Token 凭据异常: {}", ex.getMessage());
            }
        }

        return execution.execute(request, body);
    }

    /**
     * 解析生效的 Same-Token 请求头名称
     *
     * @return 请求头名称
     */
    public String resolveHeaderName() {
        if (this.headerName != null && !this.headerName.isBlank()) {
            return this.headerName;
        }
        if (properties != null && properties.getSameToken() != null && properties.getSameToken().getHeaderName() != null) {
            return properties.getSameToken().getHeaderName();
        }
        return DEFAULT_HEADER_NAME;
    }
}
