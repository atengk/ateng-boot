package io.github.atengk.boot.job.xxl.security;

import io.github.atengk.boot.job.xxl.executor.AtengXxlJobSpringExecutor;
import io.github.atengk.boot.job.xxl.mock.MockXxlJobAdminServer;
import io.github.atengk.boot.job.xxl.properties.XxlJobProperties;
import io.github.atengk.boot.job.xxl.resolver.XxlJobPortResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * XXL-Job 入站安全防火墙 (CIDR 白名单与 AccessToken 快速校验) 端到端集成测试
 *
 * @author Ateng
 * @since 2026-10-31
 */
class AtengXxlJobSecurityE2ETest {

    private MockXxlJobAdminServer mockAdmin;
    private AtengXxlJobSpringExecutor executor;
    private HttpClient httpClient;

    @BeforeEach
    void setUp() {
        mockAdmin = new MockXxlJobAdminServer();
        mockAdmin.start();
        httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .build();
    }

    @AfterEach
    void tearDown() {
        if (executor != null && executor.isRunning()) {
            executor.stop();
        }
        if (mockAdmin != null) {
            mockAdmin.close();
        }
    }

    @Test
    @DisplayName("测试合法 CIDR 与有效 AccessToken 请求成功放行 (HTTP 200)")
    void testAuthorizedRequestSuccess() throws Exception {
        int port = XxlJobPortResolver.allocateRandomPort();
        XxlJobProperties props = new XxlJobProperties();
        props.setAdminAddresses(mockAdmin.getAddress());
        props.setAppname("security-test-app");
        props.setAccessToken("test-secret-token");
        props.setPort(port);
        props.setIp("127.0.0.1");
        props.setCidrWhitelist(List.of("127.0.0.1"));

        executor = new AtengXxlJobSpringExecutor(props);
        executor.setAdminAddresses(props.getAdminAddresses());
        executor.setAppname(props.getAppname());
        executor.setAccessToken(props.getAccessToken());
        executor.setPort(props.getPort());
        executor.setIp(props.getIp());
        executor.start();

        // 向 Netty 发送 /beat 请求
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + "/beat"))
                .header("Content-Type", "application/json")
                .header("XXL-JOB-ACCESS-TOKEN", "test-secret-token")
                .header("XXL-JOB-APPNAME", "security-test-app")
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("\"code\":200"));
    }

    @Test
    @DisplayName("测试非法 AccessToken 被快速拦截并返回 HTTP 403 Forbidden")
    void testInvalidAccessTokenRejected() throws Exception {
        int port = XxlJobPortResolver.allocateRandomPort();
        XxlJobProperties props = new XxlJobProperties();
        props.setAdminAddresses(mockAdmin.getAddress());
        props.setAppname("security-test-app");
        props.setAccessToken("expected-correct-token");
        props.setPort(port);
        props.setIp("127.0.0.1");
        props.setCidrWhitelist(List.of("127.0.0.1"));

        executor = new AtengXxlJobSpringExecutor(props);
        executor.setAdminAddresses(props.getAdminAddresses());
        executor.setAppname(props.getAppname());
        executor.setAccessToken(props.getAccessToken());
        executor.setPort(props.getPort());
        executor.setIp(props.getIp());
        executor.start();

        // 携带错误 Token
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + "/beat"))
                .header("Content-Type", "application/json")
                .header("XXL-JOB-ACCESS-TOKEN", "wrong-token")
                .header("XXL-JOB-APPNAME", "security-test-app")
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(403, response.statusCode());
        assertTrue(response.body().contains("Forbidden: Invalid AccessToken"));
    }

    @Test
    @DisplayName("测试非白名单 CIDR 来源 IP 被拦截并返回 HTTP 403 Forbidden")
    void testDisallowedCidrRejected() throws Exception {
        int port = XxlJobPortResolver.allocateRandomPort();
        XxlJobProperties props = new XxlJobProperties();
        props.setAdminAddresses(mockAdmin.getAddress());
        props.setAppname("security-test-app");
        props.setAccessToken("test-token");
        props.setPort(port);
        props.setIp("127.0.0.1");
        // 配置仅允许外部网段，排除 127.0.0.1
        props.setCidrWhitelist(List.of("10.10.10.0/24"));

        executor = new AtengXxlJobSpringExecutor(props);
        executor.setAdminAddresses(props.getAdminAddresses());
        executor.setAppname(props.getAppname());
        executor.setAccessToken(props.getAccessToken());
        executor.setPort(props.getPort());
        executor.setIp(props.getIp());
        executor.start();

        // 来自 127.0.0.1 的请求应当被 CIDR 防火墙直接拦截
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + "/beat"))
                .header("Content-Type", "application/json")
                .header("XXL-JOB-ACCESS-TOKEN", "test-token")
                .header("XXL-JOB-APPNAME", "security-test-app")
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(403, response.statusCode());
        assertTrue(response.body().contains("Forbidden: Client IP not allowed by CIDR whitelist"));
    }

    @Test
    @DisplayName("测试执行器启动时端口占用自动自愈顺延，不发生崩溃")
    void testPortConflictAutoHealingOnStartup() throws IOException {
        // 先占用一个端口
        ServerSocket conflictSocket = new ServerSocket(0);
        int occupiedPort = conflictSocket.getLocalPort();

        try {
            XxlJobProperties props = new XxlJobProperties();
            props.setAdminAddresses(mockAdmin.getAddress());
            props.setAppname("port-healing-app");
            props.setAccessToken("test-token");
            props.setPort(occupiedPort); // 传入被占用的端口
            props.setIp("127.0.0.1");

            executor = new AtengXxlJobSpringExecutor(props);
            executor.setAdminAddresses(props.getAdminAddresses());
            executor.setAppname(props.getAppname());
            executor.setAccessToken(props.getAccessToken());
            executor.setPort(props.getPort());
            executor.setIp(props.getIp());

            // 启动时应当自愈，不抛出 BindException，且分配新端口
            assertDoesNotThrow(() -> executor.start());
            assertTrue(executor.getPort() > occupiedPort, "自愈顺延后的端口应当大于原被占用端口");
            assertTrue(executor.isRunning(), "执行器应当保持正常运行状态");
        } finally {
            conflictSocket.close();
        }
    }

}
