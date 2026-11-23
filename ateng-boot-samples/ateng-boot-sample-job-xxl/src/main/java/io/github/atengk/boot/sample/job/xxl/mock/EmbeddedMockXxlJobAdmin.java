package io.github.atengk.boot.sample.job.xxl.mock;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import io.github.atengk.boot.job.xxl.properties.XxlJobProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 离线自洽轻量级内嵌 Mock XXL-Job 调度中心组件（仅在非 {@code local} Profile 下激活）
 *
 * <p>在本地 {@code mvn spring-boot:run} 启动或 CI 离线端到端测试期间，自动绑定随机空闲回环端口并抢先回写
 * {@link XxlJobProperties#setAdminAddresses(String)}，同步响应执行器注册、注销、调度结果回调及 OpenAPI 全系操作，
 * 彻底消除默认指向 8080 端口产生的自环心跳 404 告警；当激活 {@code local} Profile 时自动退避不装配。</p>
 *
 * @author Ateng
 * @since 2026-11-19
 */
@Component
@Profile("!local")
public class EmbeddedMockXxlJobAdmin implements BeanPostProcessor, DisposableBean, Ordered {

    private static final Logger log = LoggerFactory.getLogger(EmbeddedMockXxlJobAdmin.class);

    private static final String SUCCESS_RESPONSE = "{\"code\":200,\"msg\":\"Success\",\"data\":\"101\"}";

    private final ReentrantLock lifecycleLock = new ReentrantLock();
    private final List<String> recordedRequestPaths = new CopyOnWriteArrayList<>();

    private HttpServer httpServer;
    private volatile int port = -1;

    @Override
    public int getOrder() {
        // 晚于 ConfigurationPropertiesBindingPostProcessor 执行，保证先完成 YAML 属性绑定再回写 Mock 地址
        return Ordered.LOWEST_PRECEDENCE;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        if (bean instanceof XxlJobProperties properties) {
            ensureStarted();
            String mockUrl = "http://127.0.0.1:" + this.port;
            properties.setAdminAddresses(mockUrl);
            log.info(">>>>>> [MockXxlJobAdmin] 非 local 模式已自动装配内嵌 Mock Admin [{}]，无需外部调度中心即可开箱联调", mockUrl);
        }
        return bean;
    }

    /**
     * 确保内嵌 Mock HTTP 服务仅启动一次（线程安全、幂等）
     */
    public void ensureStarted() {
        lifecycleLock.lock();
        try {
            if (this.httpServer != null) {
                return;
            }
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            HttpHandler handler = new MockAdminHttpHandler(this.recordedRequestPaths);
            server.createContext("/api", handler);
            server.createContext("/xxl-job-admin/api", handler);
            server.setExecutor(null);
            server.start();
            this.httpServer = server;
            this.port = server.getAddress().getPort();
        } catch (IOException e) {
            throw new IllegalStateException("启动内嵌 Mock XXL-Job Admin 服务失败: " + e.getMessage(), e);
        } finally {
            if (lifecycleLock.isHeldByCurrentThread()) {
                lifecycleLock.unlock();
            }
        }
    }

    @Override
    public void destroy() {
        lifecycleLock.lock();
        try {
            if (this.httpServer != null) {
                this.httpServer.stop(0);
                log.info(">>>>>> [MockXxlJobAdmin] 内嵌 Mock Admin 服务已安全停止 (port={})", this.port);
                this.httpServer = null;
                this.port = -1;
            }
        } finally {
            if (lifecycleLock.isHeldByCurrentThread()) {
                lifecycleLock.unlock();
            }
        }
    }

    /**
     * 获取当前内嵌 Mock Admin 绑定的真实随机端口（未启动返回 -1）
     *
     * @return 监听端口
     */
    public int getPort() {
        return this.port;
    }

    /**
     * 获取服务是否处于运行态
     *
     * @return true 若运行中
     */
    public boolean isRunning() {
        return this.httpServer != null && this.port > 0;
    }

    /**
     * 获取已接收的调度中心协议请求路径快照（用于集成测试审计校验）
     *
     * @return 请求路径只读列表
     */
    public List<String> getRecordedRequestPaths() {
        return Collections.unmodifiableList(this.recordedRequestPaths);
    }

    private static final class MockAdminHttpHandler implements HttpHandler {

        private final List<String> recordedRequestPaths;

        private MockAdminHttpHandler(List<String> recordedRequestPaths) {
            this.recordedRequestPaths = recordedRequestPaths;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            // 排空请求体保证 HTTP keep-alive 连接干净复用
            try (InputStream is = exchange.getRequestBody()) {
                is.readAllBytes();
            }
            if (exchange.getRequestURI() != null) {
                this.recordedRequestPaths.add(exchange.getRequestURI().getPath());
            }
            byte[] responseBytes = SUCCESS_RESPONSE.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            exchange.sendResponseHeaders(200, responseBytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(responseBytes);
            }
        }
    }
}
