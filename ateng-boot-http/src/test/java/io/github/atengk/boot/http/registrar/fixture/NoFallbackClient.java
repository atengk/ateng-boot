package io.github.atengk.boot.http.registrar.fixture;

import io.github.atengk.boot.http.annotation.HttpClient;
import org.springframework.web.service.annotation.GetExchange;

/**
 * 未配置 fallback 的声明式测试客户端接口
 *
 * @author Ateng
 * @since 2026-10-29
 */
@HttpClient(name = "no-fallback-service", url = "${test.no-fallback.url:http://localhost:8080}")
public interface NoFallbackClient {

    @GetExchange("/test")
    String test();

}
