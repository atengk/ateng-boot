package io.github.atengk.boot.http.registrar.fixture.explicit;

import io.github.atengk.boot.http.annotation.HttpClient;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;

/**
 * 显式导入测试用客户端接口
 *
 * @author Ateng
 * @since 2026-10-21
 */
@HttpClient(name = "explicit-service", url = "http://localhost:8082")
@HttpExchange("/api/explicit")
public interface SampleExplicitClient {

    @GetExchange("/ping")
    String ping();

}
