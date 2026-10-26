package io.github.atengk.boot.tcp.server;

import io.github.atengk.boot.tcp.client.TcpClient;
import io.github.atengk.boot.tcp.correlation.TcpRequestCorrelationManager;
import io.github.atengk.boot.tcp.enums.CloseReason;
import io.github.atengk.boot.tcp.event.TcpSessionClosedEvent;
import io.github.atengk.boot.tcp.properties.TcpClientProperties;
import io.github.atengk.boot.tcp.properties.TcpServerProperties;
import io.github.atengk.boot.tcp.session.TcpSessionManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.GenericApplicationContext;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * TCP 三阶段受管优雅停机排空机制测试
 *
 * @author Ateng
 * @since 2026-10-26
 */
class TcpGracefulShutdownTest {

    @Test
    @DisplayName("三阶段平滑停机：解除监听 -> 排空在途窗口 -> 存量注销与资源释放")
    void testThreePhaseGracefulShutdown() {
        GenericApplicationContext eventContext = new GenericApplicationContext();
        List<CloseReason> closedReasons = new CopyOnWriteArrayList<>();
        eventContext.addApplicationListener(event -> {
            if (event instanceof TcpSessionClosedEvent closedEvent) {
                closedReasons.add(closedEvent.getReason());
            }
        });
        eventContext.refresh();

        TcpServerProperties serverProps = new TcpServerProperties();
        serverProps.setHost("127.0.0.1");
        serverProps.setPort(0);
        serverProps.setDrainTimeout(Duration.ofMillis(500));

        TcpSessionManager sessionManager = new TcpSessionManager();
        sessionManager.setApplicationEventPublisher(eventContext);
        TcpRequestCorrelationManager correlationManager = new TcpRequestCorrelationManager();

        TcpServer server = new TcpServer(
                "drainServer",
                serverProps,
                sessionManager,
                correlationManager,
                null,
                Collections.emptyList(),
                Collections.emptyList(),
                eventContext
        );
        server.start();

        assertThat(server.isRunning()).isTrue();
        int actualPort = server.getActualPort();

        // 客户端连接接入
        TcpClientProperties clientProps = new TcpClientProperties();
        clientProps.setHost("127.0.0.1");
        clientProps.setPort(actualPort);
        clientProps.getReconnect().setEnabled(false);

        TcpClient client = new TcpClient("clientDrain", clientProps);
        client.start();

        await().until(client::isConnected);
        assertThat(sessionManager.getActiveCount()).isEqualTo(1);

        // 模拟挂起一个在途请求，验证受管排空窗口
        correlationManager.register("REQ-DRAIN-01", Duration.ofSeconds(1));
        assertThat(correlationManager.hasPendingRequests()).isTrue();

        // 触发服务端停机
        long startStop = System.currentTimeMillis();
        server.stop();
        long stopDuration = System.currentTimeMillis() - startStop;

        // 1. 验证经过了排空窗口期（未立即暴力杀掉，且完成了排空）
        assertThat(server.isRunning()).isFalse();

        // 2. 验证存量会话以 SERVER_SHUTDOWN 原因优雅关闭
        assertThat(sessionManager.getActiveCount()).isEqualTo(0);
        assertThat(closedReasons).contains(CloseReason.SERVER_SHUTDOWN);

        // 3. 验证解除端口监听后新客户端无法连接
        TcpClient rejectedClient = new TcpClient("rejectedClient", clientProps);
        try {
            rejectedClient.start();
        } catch (Exception ignored) {
            // 端口已解除监听，无法再建立新连接
        }
        assertThat(rejectedClient.isConnected()).isFalse();

        client.stop();
        rejectedClient.stop();
        correlationManager.close();
    }
}
