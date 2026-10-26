package io.github.atengk.boot.tcp.actuator;

import io.github.atengk.boot.tcp.client.TcpClient;
import io.github.atengk.boot.tcp.client.TcpClientRegistry;
import io.github.atengk.boot.tcp.metric.TcpMetricsCollector;
import io.github.atengk.boot.tcp.properties.TcpClientProperties;
import io.github.atengk.boot.tcp.properties.TcpServerProperties;
import io.github.atengk.boot.tcp.server.TcpServer;
import io.github.atengk.boot.tcp.server.TcpServerRegistry;
import io.github.atengk.boot.tcp.session.TcpSession;
import io.github.atengk.boot.tcp.session.TcpSessionManager;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Collections;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TCP 云原生可观测性与 Actuator 端点集成测试
 *
 * @author Ateng
 * @since 2026-10-26
 */
class TcpObservabilityIntegrationTest {

    @Test
    @DisplayName("测试在无 MeterRegistry 环境下 TcpMetricsCollector 影子计数器正常工作")
    void testMetricsCollectorWithoutMeterRegistry() {
        TcpMetricsCollector collector = new TcpMetricsCollector();

        collector.recordConnection();
        collector.recordConnection();
        assertThat(collector.getConnectionsTotal()).isEqualTo(2);

        collector.recordMessageReceived(1024);
        collector.recordMessageSent(2048);
        assertThat(collector.getBytesReceived()).isEqualTo(1024);
        assertThat(collector.getBytesSent()).isEqualTo(2048);
        assertThat(collector.getMessagesReceived()).isEqualTo(1);
        assertThat(collector.getMessagesSent()).isEqualTo(1);

        collector.recordRequestDuration(Duration.ofMillis(50));
    }

    @Test
    @DisplayName("测试结合 Micrometer SimpleMeterRegistry 网络黄金指标正常注册与递增")
    void testMetricsCollectorWithMeterRegistry() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        TcpSessionManager sessionManager = new TcpSessionManager();
        TcpMetricsCollector collector = new TcpMetricsCollector(registry, sessionManager);

        collector.recordConnection();
        collector.recordMessageReceived(512);
        collector.recordMessageSent(256);
        collector.recordRequestDuration(Duration.ofMillis(20));

        // 验证 Gauge 初始为 0（无活跃连接）
        assertThat(registry.get("ateng.tcp.connections.active").gauge().value()).isEqualTo(0.0);

        // 模拟注册一个活跃会话
        EmbeddedChannel channel = new EmbeddedChannel();
        TcpSession session = new TcpSession("SESS-METER-01", channel);
        sessionManager.register(session);

        // 验证 Gauge 动态反映活跃连接数
        assertThat(registry.get("ateng.tcp.connections.active").gauge().value()).isEqualTo(1.0);

        // 验证 Counters
        assertThat(registry.get("ateng.tcp.connections.total").counter().count()).isEqualTo(1.0);
        assertThat(registry.get("ateng.tcp.bytes.received").counter().count()).isEqualTo(512.0);
        assertThat(registry.get("ateng.tcp.bytes.sent").counter().count()).isEqualTo(256.0);
        assertThat(registry.get("ateng.tcp.messages.received").counter().count()).isEqualTo(1.0);
        assertThat(registry.get("ateng.tcp.messages.sent").counter().count()).isEqualTo(1.0);

        // 验证 Timer
        assertThat(registry.get("ateng.tcp.request.duration").timer().count()).isEqualTo(1);

        channel.close();
    }

    @Test
    @DisplayName("测试 /actuator/tcp 管控端点信息暴露与单设备穿透诊断")
    void testActuatorEndpoint() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        TcpSessionManager sessionManager = new TcpSessionManager();
        TcpMetricsCollector collector = new TcpMetricsCollector(registry, sessionManager);

        TcpServerRegistry serverRegistry = new TcpServerRegistry();
        TcpServerProperties serverProps = new TcpServerProperties();
        serverProps.setPort(9999);
        TcpServer server = new TcpServer("testServer", serverProps, sessionManager, null, null, Collections.emptyList(), Collections.emptyList(), null, collector);
        serverRegistry.register("testServer", server);

        TcpClientRegistry clientRegistry = new TcpClientRegistry();
        TcpClientProperties clientProps = new TcpClientProperties();
        clientProps.setPort(9999);
        TcpClient client = new TcpClient("testClient", clientProps);
        clientRegistry.register("testClient", client);

        TcpActuatorEndpoint endpoint = new TcpActuatorEndpoint(
                sessionManager,
                serverRegistry,
                clientRegistry,
                collector
        );

        // 1. 测试整体 info 概览
        Map<String, Object> info = endpoint.info();
        assertThat(info).isNotNull();
        assertThat(info.get("status")).isEqualTo("UP");
        assertThat(info.get("activeSessions")).isEqualTo(0);
        assertThat(info.get("servers")).isInstanceOf(Map.class);
        assertThat(info.get("clients")).isInstanceOf(Map.class);
        assertThat(info.get("metrics")).isInstanceOf(Map.class);

        // 2. 模拟一个设备会话并绑定
        EmbeddedChannel channel = new EmbeddedChannel();
        TcpSession session = new TcpSession("SESS-001", channel);
        sessionManager.register(session);
        sessionManager.bind("SESS-001", "DEV-ACTUATOR-888");

        // 3. 测试设备穿透查询
        Map<String, Object> deviceDetail = endpoint.sessionDetail("DEV-ACTUATOR-888");
        assertThat(deviceDetail).isNotNull();
        assertThat(deviceDetail.get("deviceId")).isEqualTo("DEV-ACTUATOR-888");
        assertThat(deviceDetail.get("sessionId")).isEqualTo("SESS-001");
        assertThat(deviceDetail.get("active")).isEqualTo(true);

        // 4. 测试未命中设备查询
        Map<String, Object> notFound = endpoint.sessionDetail("NON-EXISTENT");
        assertThat(notFound.get("found")).isEqualTo(false);
        assertThat(notFound.get("deviceId")).isEqualTo("NON-EXISTENT");

        channel.close();
    }
}
