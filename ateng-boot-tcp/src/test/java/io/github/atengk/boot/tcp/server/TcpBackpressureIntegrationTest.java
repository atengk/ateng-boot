package io.github.atengk.boot.tcp.server;

import io.github.atengk.boot.tcp.enums.BackpressurePolicy;
import io.github.atengk.boot.tcp.event.TcpChannelWritabilityChangedEvent;
import io.github.atengk.boot.tcp.exception.TcpBackpressureException;
import io.github.atengk.boot.tcp.properties.TcpServerProperties;
import io.github.atengk.boot.tcp.session.TcpSession;
import io.github.atengk.boot.tcp.session.TcpSessionManager;
import io.github.atengk.boot.tcp.template.TcpServerTemplate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;

import java.io.InputStream;
import java.net.Socket;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/**
 * TCP 出站缓冲区高低水位与背压流控端到端集成测试
 *
 * @author Ateng
 * @since 2026-11-02
 */
@DisplayName("TCP 出站高低水位与背压流控集成测试")
class TcpBackpressureIntegrationTest {

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

    private void startServer(TcpServerProperties properties) {
        sessionManager = new TcpSessionManager();
        sessionManager.setApplicationEventPublisher(publishedEvents::add);
        serverTemplate = new TcpServerTemplate(sessionManager);

        tcpServer = new TcpServer(
                properties,
                sessionManager,
                Collections.emptyList(),
                Collections.emptyList(),
                publishedEvents::add
        );
        tcpServer.start();
    }

    @Test
    @DisplayName("验证 FAIL_FAST 策略: 慢客户端导致缓冲区达高水位时快速熔断并发布可写状态跃迁事件")
    void testBackpressureFailFastWhenBufferSaturates() throws Exception {
        TcpServerProperties properties = new TcpServerProperties();
        properties.setHost("127.0.0.1");
        properties.setPort(0);
        // 设置极小高低水位便于快速饱和
        properties.setWriteBufferLowWaterMark(DataSize.ofBytes(1024));
        properties.setWriteBufferHighWaterMark(DataSize.ofBytes(2048));
        properties.setBackpressurePolicy(BackpressurePolicy.FAIL_FAST);

        startServer(properties);
        int port = tcpServer.getActualPort();

        // 创建不主动读取数据的慢客户端，设置极小的 TCP 接收窗口
        try (Socket clientSocket = new Socket("127.0.0.1", port)) {
            clientSocket.setReceiveBufferSize(1024);

            await().atMost(3, TimeUnit.SECONDS).until(() -> !sessionManager.getAllSessions().isEmpty());
            TcpSession session = sessionManager.getAllSessions().iterator().next();

            assertThat(session.isWritable()).isTrue();

            byte[] largeChunk = new byte[8192];
            boolean backpressureTriggered = false;

            // 持续下发大报文填满底层 TCP 窗口与 Netty 出站缓冲
            for (int i = 0; i < 2000; i++) {
                try {
                    serverTemplate.send(session.getSessionId(), largeChunk);
                } catch (TcpBackpressureException ex) {
                    backpressureTriggered = true;
                    assertThat(ex.getMessage()).contains("触发背压快速失败");
                    break;
                }
            }

            assertThat(backpressureTriggered).as("持续向未消费的慢客户端推流应当触发高水位背压").isTrue();

            // 验证是否发布了可写状态变迁事件 (writable = false)
            await().atMost(3, TimeUnit.SECONDS).until(() ->
                    publishedEvents.stream().anyMatch(e -> e instanceof TcpChannelWritabilityChangedEvent event && !event.isWritable())
            );

            // 客户端启动后台消费线程抽干数据，驱动 TCP 窗口滑动与 Netty 出站缓冲排空
            java.util.concurrent.atomic.AtomicBoolean stopReader = new java.util.concurrent.atomic.AtomicBoolean(false);
            Thread readerThread = new Thread(() -> {
                try {
                    InputStream in = clientSocket.getInputStream();
                    byte[] buf = new byte[16384];
                    while (!stopReader.get()) {
                        int read = in.read(buf);
                        if (read == -1) {
                            break;
                        }
                    }
                } catch (Exception ignored) {
                }
            });
            readerThread.setDaemon(true);
            readerThread.start();

            try {
                // 等待出站缓冲区排空回落至低水位恢复可写
                await().atMost(5, TimeUnit.SECONDS).until(session::isWritable);

                // 验证是否发布了恢复可写的变迁事件 (writable = true)
                await().atMost(3, TimeUnit.SECONDS).until(() ->
                        publishedEvents.stream().anyMatch(e -> e instanceof TcpChannelWritabilityChangedEvent event && event.isWritable())
                );
            } finally {
                stopReader.set(true);
                readerThread.interrupt();
            }
        }
    }

    @Test
    @DisplayName("验证 BLOCK_TIMEOUT 策略: 通道不可写时超时抛出 TcpBackpressureException")
    void testBackpressureBlockTimeoutPolicy() throws Exception {
        TcpServerProperties properties = new TcpServerProperties();
        properties.setHost("127.0.0.1");
        properties.setPort(0);
        properties.setWriteBufferLowWaterMark(DataSize.ofBytes(1024));
        properties.setWriteBufferHighWaterMark(DataSize.ofBytes(2048));
        properties.setBackpressurePolicy(BackpressurePolicy.BLOCK_TIMEOUT);
        properties.setBackpressureTimeout(Duration.ofMillis(100));

        startServer(properties);
        int port = tcpServer.getActualPort();

        try (Socket clientSocket = new Socket("127.0.0.1", port)) {
            clientSocket.setReceiveBufferSize(1024);

            await().atMost(3, TimeUnit.SECONDS).until(() -> !sessionManager.getAllSessions().isEmpty());
            TcpSession session = sessionManager.getAllSessions().iterator().next();

            byte[] largeChunk = new byte[8192];
            boolean backpressureTriggered = false;

            for (int i = 0; i < 2000; i++) {
                try {
                    serverTemplate.send(session.getSessionId(), largeChunk);
                } catch (TcpBackpressureException ex) {
                    backpressureTriggered = true;
                    assertThat(ex.getMessage()).contains("等待可写超时");
                    break;
                }
            }

            assertThat(backpressureTriggered).isTrue();
        }
    }

    @Test
    @DisplayName("验证 WARN_AND_SEND 策略: 通道不可写时记录告警并放行写入")
    void testBackpressureWarnAndSendPolicy() throws Exception {
        TcpServerProperties properties = new TcpServerProperties();
        properties.setHost("127.0.0.1");
        properties.setPort(0);
        properties.setWriteBufferLowWaterMark(DataSize.ofBytes(1024));
        properties.setWriteBufferHighWaterMark(DataSize.ofBytes(2048));
        properties.setBackpressurePolicy(BackpressurePolicy.WARN_AND_SEND);

        startServer(properties);
        int port = tcpServer.getActualPort();

        try (Socket clientSocket = new Socket("127.0.0.1", port)) {
            clientSocket.setReceiveBufferSize(1024);

            await().atMost(3, TimeUnit.SECONDS).until(() -> !sessionManager.getAllSessions().isEmpty());
            TcpSession session = sessionManager.getAllSessions().iterator().next();

            byte[] largeChunk = new byte[8192];
            // 在 WARN_AND_SEND 策略下，不应抛出 TcpBackpressureException
            for (int i = 0; i < 20; i++) {
                serverTemplate.send(session.getSessionId(), largeChunk);
            }
        }
    }
}
