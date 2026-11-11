package io.github.atengk.boot.sample.job.xxl;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * 离线轻量级 Mock XXL-Job 调度中心 HTTP 分发器
 *
 * <p>为样例工程端到端测试提供确定性离线 Mock Admin 响应，模拟调度中心 OpenAPI 成功返回，
 * 消除对外部真实 XXL-Job 调度中心进程的依赖，确保测试离线自洽。</p>
 *
 * @author Ateng
 * @since 2026-11-11
 */
public class MockAdminDispatcher implements HttpHandler {

    private static final String SUCCESS_RESPONSE = "{\"code\":200,\"msg\":\"Success\",\"data\":\"101\"}";

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        byte[] responseBytes = SUCCESS_RESPONSE.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(200, responseBytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(responseBytes);
        }
    }
}
