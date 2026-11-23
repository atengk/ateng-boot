package io.github.atengk.boot.http.registrar.errorfixture;

import io.github.atengk.boot.http.annotation.HttpClient;
import org.springframework.web.service.annotation.GetExchange;

/**
 * 配置了非法 fallback（未实现接口）的客户端接口（放置于 errorfixture 避免被常规包扫描误伤）
 *
 * @author Ateng
 * @since 2026-10-29
 */
@HttpClient(name = "invalid-fallback-service", fallback = String.class)
public interface InvalidFallbackClient {

    @GetExchange("/test")
    String test();

}
