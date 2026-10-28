package io.github.atengk.boot.tcp.server;

import io.github.atengk.boot.tcp.enums.CloseReason;
import io.github.atengk.boot.tcp.enums.FramingMode;
import io.github.atengk.boot.tcp.event.TcpDeviceBoundEvent;
import io.github.atengk.boot.tcp.event.TcpSessionClosedEvent;
import io.github.atengk.boot.tcp.event.TcpSessionConnectedEvent;
import io.github.atengk.boot.tcp.model.TcpPayload;
import io.github.atengk.boot.tcp.properties.TcpServerProperties;
import io.github.atengk.boot.tcp.session.TcpSession;
import io.github.atengk.boot.tcp.session.TcpSessionManager;
import io.github.atengk.boot.tcp.spi.TcpChannelCustomizer;
import io.github.atengk.boot.tcp.template.TcpServerTemplate;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEvent;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TCP 服务端网络生命周期与双工通信端到端集成测试
 *
 * @author Ateng
 * @since 2026-10-26
 */
@DisplayName("TCP 服务端双工通信与会话治理端到端集成测试")
class TcpServerIntegrationTest {

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

    private void startServer(TcpServerProperties properties, List<TcpChannelCustomizer> customizers) {
        sessionManager = new TcpSessionManager();
        sessionManager.setApplicationEventPublisher(publishedEvents::add);
        serverTemplate = new TcpServerTemplate(sessionManager);

        tcpServer = new TcpServer(
                properties,
                sessionManager,
                Collections.emptyList(),
                customizers != null ? customizers : Collections.emptyList(),
                publishedEvents::add
        );
        tcpServer.start();
    }

    @Test
    @DisplayName("验证服务端随机端口监听、客户端连接建立与正常断开全流程")
    void testServerStartupAndClientConnection() throws Exception {
        TcpServerProperties properties = new TcpServerProperties();
        properties.setHost("127.0.0.1");
        properties.setPort(0);

        startServer(properties, null);

        int actualPort = tcpServer.getActualPort();
        assertThat(actualPort).isGreaterThan(0);
        assertThat(tcpServer.isRunning()).isTrue();

        // 客户端连接
        try (Socket clientSocket = new Socket("127.0.0.1", actualPort)) {
            // 等待服务端 channelActive 处理并注册会话
            TimeUnit.MILLISECONDS.sleep(150);

            assertThat(sessionManager.getActiveCount()).isEqualTo(1);
            TcpSession session = sessionManager.getAllSessions().iterator().next();
            assertThat(session.isActive()).isTrue();
            assertThat(session.getRemoteAddress()).isNotNull();

            // 验证触发了连接建立事件
            assertThat(publishedEvents)
                    .filteredOn(e -> e instanceof TcpSessionConnectedEvent)
                    .hasSize(1);
        }

        // 客户端已关闭，等待服务端 channelInactive 处理
        TimeUnit.MILLISECONDS.sleep(150);

        assertThat(sessionManager.getActiveCount()).isEqualTo(0);
        assertThat(publishedEvents)
                .filteredOn(e -> e instanceof TcpSessionClosedEvent)
                .hasSize(1);
    }

    @Test
    @DisplayName("验证服务端与客户端双工数据收发互通")
    void testServerBidirectionalDataExchange() throws Exception {
        TcpServerProperties properties = new TcpServerProperties();
        properties.setHost("127.0.0.1");
        properties.setPort(0);
        properties.getFraming().setMode(FramingMode.DELIMITER);
        properties.getFraming().setDelimiter("\n");

        CountDownLatch serverReceivedLatch = new CountDownLatch(1);
        List<String> receivedByServer = new ArrayList<>();

        // 注册测试通道处理器以记录服务端接收的数据
        TcpChannelCustomizer customizer = ch -> ch.pipeline().addLast("testServerReceiver",
                new SimpleChannelInboundHandler<TcpPayload>() {
                    @Override
                    protected void channelRead0(ChannelHandlerContext ctx, TcpPayload msg) {
                        receivedByServer.add(msg.asString());
                        serverReceivedLatch.countDown();
                    }
                });

        startServer(properties, List.of(customizer));
        int port = tcpServer.getActualPort();

        try (Socket socket = new Socket("127.0.0.1", port)) {
            OutputStream out = socket.getOutputStream();
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));

            // 1. 客户端发送数据帧（带换行符符合默认 DELIMITER 分帧）
            out.write("Hello Ateng TCP\n".getBytes(StandardCharsets.UTF_8));
            out.flush();

            boolean serverGot = serverReceivedLatch.await(3, TimeUnit.SECONDS);
            assertThat(serverGot).isTrue();
            assertThat(receivedByServer).containsExactly("Hello Ateng TCP");

            // 2. 服务端通过模板下发数据
            TcpSession session = sessionManager.getAllSessions().iterator().next();
            serverTemplate.send(session.getSessionId(), "Echo: Ateng TCP\n");

            // 3. 客户端接收服务端下发的数据
            String reply = in.readLine();
            assertThat(reply).isEqualTo("Echo: Ateng TCP");
        }
    }

    @Test
    @DisplayName("验证重连场景下“踢旧留新”机制：同一设备重连时踢掉旧连接并建立新绑定")
    void testKickOutReconnection() throws Exception {
        TcpServerProperties properties = new TcpServerProperties();
        properties.setHost("127.0.0.1");
        properties.setPort(0);

        startServer(properties, null);
        int port = tcpServer.getActualPort();

        Socket client1 = new Socket("127.0.0.1", port);
        TimeUnit.MILLISECONDS.sleep(100);

        TcpSession session1 = sessionManager.getAllSessions().iterator().next();
        sessionManager.bind(session1.getSessionId(), "DEV-001");
        assertThat(sessionManager.getSessionByDeviceId("DEV-001")).isSameAs(session1);

        // 客户端 2 建立连接
        Socket client2 = new Socket("127.0.0.1", port);
        TimeUnit.MILLISECONDS.sleep(100);

        TcpSession session2 = sessionManager.getAllSessions().stream()
                .filter(s -> !s.getSessionId().equals(session1.getSessionId()))
                .findFirst().orElseThrow();

        // 绑定相同设备标识到 session2，触发踢旧留新
        sessionManager.bind(session2.getSessionId(), "DEV-001");

        // 客户端 1 应该被服务端断开，read 返回 -1 (EOF)
        int read1 = client1.getInputStream().read();
        assertThat(read1).isEqualTo(-1);
        client1.close();

        // 验证旧会话状态与关闭原因
        assertThat(session1.isActive()).isFalse();
        assertThat(session1.getCloseReason()).isEqualTo(CloseReason.KICK_OUT);

        // 验证新会话正常持有设备标识
        assertThat(sessionManager.getSessionByDeviceId("DEV-001")).isSameAs(session2);
        assertThat(session2.isActive()).isTrue();

        client2.close();
    }

    @Test
    @DisplayName("验证服务端最大连接数限制与过载拒绝保护")
    void testMaxConnectionsGuard() throws Exception {
        TcpServerProperties properties = new TcpServerProperties();
        properties.setHost("127.0.0.1");
        properties.setPort(0);
        properties.setMaxConnections(1); // 限制最多 1 个连接

        startServer(properties, null);
        int port = tcpServer.getActualPort();

        // 连接 1：应该成功维持连接
        Socket client1 = new Socket("127.0.0.1", port);
        TimeUnit.MILLISECONDS.sleep(100);
        assertThat(sessionManager.getActiveCount()).isEqualTo(1);

        // 连接 2：由于达到上限，服务端应主动断开该通道
        Socket client2 = new Socket("127.0.0.1", port);
        int readByte = client2.getInputStream().read();
        assertThat(readByte).isEqualTo(-1); // 对端主动关闭

        client1.close();
        client2.close();
    }

    @Test
    @DisplayName("验证服务端平滑停止（优雅停机）与所有存量会话清理")
    void testServerGracefulShutdown() throws Exception {
        TcpServerProperties properties = new TcpServerProperties();
        properties.setHost("127.0.0.1");
        properties.setPort(0);

        startServer(properties, null);
        int port = tcpServer.getActualPort();

        Socket client = new Socket("127.0.0.1", port);
        TimeUnit.MILLISECONDS.sleep(100);
        assertThat(sessionManager.getActiveCount()).isEqualTo(1);

        // 触发服务端停机
        tcpServer.stop();

        // 客户端连接应被关闭
        int read = client.getInputStream().read();
        assertThat(read).isEqualTo(-1);
        client.close();

        assertThat(tcpServer.isRunning()).isFalse();
        assertThat(sessionManager.getActiveCount()).isEqualTo(0);
    }
}
