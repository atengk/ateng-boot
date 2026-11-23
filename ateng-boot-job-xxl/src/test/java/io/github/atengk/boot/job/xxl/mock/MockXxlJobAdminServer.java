package io.github.atengk.boot.job.xxl.mock;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 轻量级内嵌 XXL-Job 调度中心 Mock 服务桩
 *
 * <p>基于 JDK 原生 {@link HttpServer} 构建，用于单测与 CI 环境在 100% 脱网离线下模拟调度中心交互，
 * 响应执行器发起的注册 (registry)、注销 (registryRemove) 与结果回调 (callback) 请求。</p>
 *
 * @author Ateng
 * @since 2026-10-31
 */
public class MockXxlJobAdminServer implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(MockXxlJobAdminServer.class);

    private final HttpServer server;
    private final int port;
    private final List<MockRequestRecord> recordedRequests = new CopyOnWriteArrayList<>();

    public MockXxlJobAdminServer() {
        try {
            this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            this.port = this.server.getAddress().getPort();
            this.server.createContext("/api", new DispatcherHandler());
            this.server.setExecutor(null);
        } catch (IOException e) {
            throw new RuntimeException("Failed to initialize MockXxlJobAdminServer", e);
        }
    }

    /**
     * 启动 Mock 服务
     */
    public void start() {
        this.server.start();
        log.info("MockXxlJobAdminServer 启动成功，监听地址: {}", getAddress());
    }

    /**
     * 获取完整调度中心访问地址
     */
    public String getAddress() {
        return "http://127.0.0.1:" + this.port;
    }

    /**
     * 获取当前服务端口
     */
    public int getPort() {
        return this.port;
    }

    /**
     * 获取所有捕获的请求记录
     */
    public List<MockRequestRecord> getRecordedRequests() {
        return this.recordedRequests;
    }

    /**
     * 获取所有注册请求记录
     */
    public List<MockRequestRecord> getRegistryRequests() {
        return this.recordedRequests.stream()
                .filter(r -> r.uri().endsWith("/registry"))
                .toList();
    }

    /**
     * 停止 Mock 服务
     */
    public void stop() {
        this.server.stop(0);
        log.info("MockXxlJobAdminServer 已安全停止");
    }

    @Override
    public void close() {
        stop();
    }

    /**
     * 请求派发处理器
     */
    private class DispatcherHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getPath();
            String method = exchange.getRequestMethod();
            String token = exchange.getRequestHeaders().getFirst("XXL-JOB-ACCESS-TOKEN");
            String appname = exchange.getRequestHeaders().getFirst("XXL-JOB-APPNAME");

            String body;
            try (InputStream in = exchange.getRequestBody(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[1024];
                int len;
                while ((len = in.read(buffer)) != -1) {
                    out.write(buffer, 0, len);
                }
                body = out.toString(StandardCharsets.UTF_8);
            }

            MockRequestRecord record = new MockRequestRecord(path, method, token, appname, body);
            recordedRequests.add(record);
            log.debug("Mock Admin 收到请求: path={}, appname={}, body={}", path, appname, body);

            // 返回成功的 Response JSON
            String responseJson = "{\"code\":200,\"msg\":null,\"data\":null}";
            byte[] responseBytes = responseJson.getBytes(StandardCharsets.UTF_8);

            exchange.getResponseHeaders().set("Content-Type", "application/json;charset=UTF-8");
            exchange.sendResponseHeaders(200, responseBytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(responseBytes);
            }
        }
    }

    /**
     * 捕获的请求记录模型
     */
    public record MockRequestRecord(
            String uri,
            String method,
            String accessToken,
            String appname,
            String body
    ) {
    }

}
