package io.github.atengk.boot.http.registrar.fixture;

import io.github.atengk.boot.http.annotation.HttpClient;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;

/**
 * 扫描测试用标准声明式客户端接口
 *
 * @author Ateng
 * @since 2026-10-21
 */
@HttpClient(name = "user-service", url = "${test.user-service.url:http://localhost:8080}")
@HttpExchange("/api/users")
public interface SampleUserClient {

    @GetExchange("/{id}")
    String getUserById(@PathVariable("id") Long id);

    @GetExchange("/search")
    String search(@RequestParam("keyword") String keyword);

}
