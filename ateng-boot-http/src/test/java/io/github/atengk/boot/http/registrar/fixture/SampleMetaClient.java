package io.github.atengk.boot.http.registrar.fixture;

import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;

/**
 * 元注解标记的声明式客户端接口
 *
 * @author Ateng
 * @since 2026-10-21
 */
@CustomClient
@HttpExchange("/api/custom")
public interface SampleMetaClient {

    @GetExchange("/info")
    String getInfo();

}
