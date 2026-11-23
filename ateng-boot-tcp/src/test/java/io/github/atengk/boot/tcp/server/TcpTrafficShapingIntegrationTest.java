package io.github.atengk.boot.tcp.server;

import io.github.atengk.boot.tcp.properties.TcpServerProperties;
import io.github.atengk.boot.tcp.properties.TcpTrafficShapingProperties;
import io.github.atengk.boot.tcp.session.TcpSession;
import io.github.atengk.boot.tcp.session.TcpSessionManager;
import io.github.atengk.boot.tcp.template.TcpServerTemplate;
import io.netty.handler.traffic.GlobalTrafficShapingHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * TCP 网络层流量整形与带宽限速端到端集成测试
 *
 * @author Ateng
 * @since 2026-11-02
 */
@DisplayName("TCP 网络层流量整形与带宽限速集成测试")
class TcpTrafficShapingIntegrationTest {

    private TcpServer tcpServer;
    private TcpSessionManager sessionManager;
    private TcpServerTemplate serverTemplate;
    private final List<Object> publishedEvents = new CopyOnWriteArrayList<>();

    @AfterEach
    void tearDown() {
        if (tcpServer != null && tcpServer.isRunning()) {
            tcpServer.stop();
        }
    }

    private void startServer(TcpTrafficShapingProperties tsProperties) {
        sessionManager = new TcpSessionManager();
        sessionManager.setApplicationEventPublisher(publishedEvents::add);
        serverTemplate = new TcpServerTemplate(sessionManager);

        TcpServerProperties serverProperties = new TcpServerProperties();
        serverProperties.setHost("127.0.0.1");
        serverProperties.setPort(0);
        serverProperties.setTrafficShaping(tsProperties);

        tcpServer = new TcpServer(
                serverProperties,
                sessionManager,
                Collections.emptyList(),
                Collections.emptyList(),
                publishedEvents::add
        );
        tcpServer.start();
    }

    @Test
    @DisplayName("验证全局流量整形处理器初始化、带宽配额设置与停机确定性资源释放")
    void testGlobalTrafficShapingAndResourceTeardown() {
        TcpTrafficShapingProperties properties = new TcpTrafficShapingProperties();
        properties.setEnabled(true);
        properties.setGlobalWriteLimit(DataSize.ofKilobytes(64));
        properties.setGlobalReadLimit(DataSize.ofKilobytes(128));
        properties.setCheckInterval(Duration.ofMillis(500));
        properties.setMaxTime(Duration.ofSeconds(10));

        startServer(properties);

        GlobalTrafficShapingHandler handler = tcpServer.getGlobalTrafficShapingHandler();
        assertThat(handler).isNotNull();
        assertThat(handler.getWriteLimit()).isEqualTo(64 * 1024L);
        assertThat(handler.getReadLimit()).isEqualTo(128 * 1024L);
        assertThat(handler.getCheckInterval()).isEqualTo(500L);
        assertThat(handler.getMaxTimeWait()).isEqualTo(10000L);

        // 验证停止服务端后全局流量整形处理器与调度资源确定性释放注销
        tcpServer.stop();
        assertThat(tcpServer.getGlobalTrafficShapingHandler()).isNull();
    }

    @Test
    @DisplayName("验证流量整形限速削峰填谷: 突发写入受管平滑下发且报文无损零丢包")
    void testTrafficShapingSmoothTransmissionWithoutFrameDrop() throws Exception {
        TcpTrafficShapingProperties properties = new TcpTrafficShapingProperties();
        properties.setEnabled(true);
        // 设置单连接与全局写入限速为 50KB/s
        properties.setGlobalWriteLimit(DataSize.ofKilobytes(50));
        properties.setChannelWriteLimit(DataSize.ofKilobytes(50));
        properties.setCheckInterval(Duration.ofMillis(200));

        startServer(properties);
        int port = tcpServer.getActualPort();

        try (Socket clientSocket = new Socket("127.0.0.1", port)) {
            clientSocket.setSoTimeout(5000);
            await().atMost(3, TimeUnit.SECONDS).until(() -> !sessionManager.getAllSessions().isEmpty());
            TcpSession session = sessionManager.getAllSessions().iterator().next();

            // 发送突发测试载荷 (10KB 结构化数据)
            byte[] payload = new byte[10240];
            Arrays.fill(payload, (byte) 0x5A);

            serverTemplate.send(session.getSessionId(), payload);

            // 客户端读取并验证完整无损接收
            InputStream in = clientSocket.getInputStream();
            byte[] received = new byte[payload.length];
            int totalRead = 0;
            while (totalRead < received.length) {
                int read = in.read(received, totalRead, received.length - totalRead);
                if (read == -1) {
                    break;
                }
                totalRead += read;
            }

            assertThat(totalRead).isEqualTo(payload.length);
            assertThat(received).isEqualTo(payload);
        }
    }

    @Test
    @DisplayName("验证未启用流量整形时处理器不装配")
    void testTrafficShapingDisabled() throws Exception {
        TcpTrafficShapingProperties properties = new TcpTrafficShapingProperties();
        properties.setEnabled(false);
        properties.setGlobalWriteLimit(DataSize.ofKilobytes(100));

        startServer(properties);
        int port = tcpServer.getActualPort();

        assertThat(tcpServer.getGlobalTrafficShapingHandler()).isNull();

        try (Socket clientSocket = new Socket("127.0.0.1", port)) {
            await().atMost(3, TimeUnit.SECONDS).until(() -> !sessionManager.getAllSessions().isEmpty());
            TcpSession session = sessionManager.getAllSessions().iterator().next();

            byte[] payload = "NO_LIMIT_TEST".getBytes();
            serverTemplate.send(session.getSessionId(), payload);

            InputStream in = clientSocket.getInputStream();
            byte[] buf = new byte[payload.length];
            int read = in.read(buf);

            assertThat(read).isEqualTo(payload.length);
            assertThat(buf).isEqualTo(payload);
        }
    }
}
