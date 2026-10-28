package io.github.atengk.boot.notification;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

/**
 * 协同通知基础离线测试基类
 * 提供纯内存 In-Memory MockWebServer 物理服务生命周期、动态端口分配与请求断言辅助
 *
 * @author Ateng
 * @since 2026-10-23
 */
public abstract class BaseNotificationTest {

    protected MockWebServer server;

    @BeforeEach
    void setUpServer() throws IOException {
        server = new MockWebServer();
        server.start();
    }

    @AfterEach
    void tearDownServer() throws IOException {
        if (server != null) {
            server.shutdown();
        }
    }

    /**
     * 获取测试服务器绑定的绝对根 URL（如 http://localhost:54321/）
     */
    protected String getBaseUrl() {
        return server.url("/").toString();
    }

    /**
     * 获取指定路径的完整 Webhook URL
     *
     * @param path 相对路径，如 "webhook/send"
     * @return 完整可用地址
     */
    protected String getWebhookUrl(String path) {
        String cleanPath = path.startsWith("/") ? path.substring(1) : path;
        return server.url("/" + cleanPath).toString();
    }

    /**
     * 入队模拟成功响应（200 OK + application/json）
     *
     * @param jsonBody 响应体 JSON 字符串
     */
    protected void enqueueOkJson(String jsonBody) {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json; charset=utf-8")
                .setBody(jsonBody));
    }

    /**
     * 入队自定义状态码与响应体
     *
     * @param statusCode HTTP 状态码
     * @param body       响应体
     */
    protected void enqueueResponse(int statusCode, String body) {
        server.enqueue(new MockResponse()
                .setResponseCode(statusCode)
                .setHeader("Content-Type", "application/json; charset=utf-8")
                .setBody(body));
    }

    /**
     * 获取并校验服务器接收到的物理出站请求（最多等待 3 秒）
     */
    protected RecordedRequest takeRequest() throws InterruptedException {
        return server.takeRequest(3, TimeUnit.SECONDS);
    }
}
