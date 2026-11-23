package io.github.atengk.boot.http.registrar.fixture;

import io.github.atengk.boot.http.annotation.HttpClient;
import io.github.atengk.boot.http.annotation.HttpTimeout;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;

/**
 * 带有 @HttpTimeout 与 fallback 配置的测试客户端接口
 *
 * @author Ateng
 * @since 2026-10-29
 */
@HttpClient(name = "timeout-fallback-service", url = "${test.timeout-fallback.url:http://localhost:8080}", fallback = TimeoutAndFallbackClientFallback.class)
@HttpTimeout(readTimeout = "3s", connectTimeout = "2s")
@HttpExchange("/api")
public interface TimeoutAndFallbackClient {

    @GetExchange("/default-timeout")
    String defaultTimeout();

    @HttpTimeout(readTimeout = "500ms")
    @GetExchange("/method-timeout")
    String methodTimeout();

    @HttpTimeout(readTimeout = "1000", connectTimeout = "500")
    @GetExchange("/pure-digits-timeout")
    String pureDigitsTimeout();

    @HttpTimeout(readTimeout = "${test.custom.timeout:4s}")
    @GetExchange("/placeholder-timeout")
    String placeholderTimeout();

    @GetExchange("/error/{code}")
    String simulateError(@PathVariable("code") int code);

    @GetExchange("/exception")
    String simulateException();

}
