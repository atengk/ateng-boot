package io.github.atengk.boot.tcp.client;

import io.github.atengk.boot.core.util.IdUtil;
import io.github.atengk.boot.tcp.correlation.TcpRequestCorrelationManager;
import io.github.atengk.boot.tcp.enums.FramingMode;
import io.github.atengk.boot.tcp.exception.TcpTimeoutException;
import io.github.atengk.boot.tcp.model.TcpPacket;
import io.github.atengk.boot.tcp.model.TcpPayload;
import io.github.atengk.boot.tcp.properties.TcpClientProperties;
import io.github.atengk.boot.tcp.properties.TcpServerProperties;
import io.github.atengk.boot.tcp.server.TcpServer;
import io.github.atengk.boot.tcp.session.TcpSession;
import io.github.atengk.boot.tcp.session.TcpSessionManager;
import io.github.atengk.boot.tcp.spi.TcpChannelCustomizer;
import io.github.atengk.boot.tcp.template.TcpClientTemplate;
import io.github.atengk.boot.tcp.template.TcpServerTemplate;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * TCP 客户端网络长连接、退避重连与双向全双工 RPC 端到端集成测试
 *
 * @author Ateng
 * @since 2026-10-26
 */
@DisplayName("TCP 客户端长连接、自愈重连与双向 RPC 集成测试")
class TcpClientIntegrationTest {

    private TcpServer server;
    private TcpSessionManager sessionManager;
    private TcpRequestCorrelationManager serverCorrelationManager;
    private TcpServerTemplate serverTemplate;

    private TcpClient client;
    private TcpRequestCorrelationManager clientCorrelationManager;
    private TcpClientTemplate clientTemplate;

    @AfterEach
    void tearDown() {
        if (client != null) {
            try {
                client.stop();
            } catch (Exception ignored) {
            }
        }
        if (clientCorrelationManager != null) {
            clientCorrelationManager.close();
        }
        if (server != null) {
            try {
                server.stop();
            } catch (Exception ignored) {
            }
        }
        if (serverCorrelationManager != null) {
            serverCorrelationManager.close();
        }
    }

    private void initServerAndClient(List<TcpChannelCustomizer> serverCustomizers,
                                    List<TcpChannelCustomizer> clientCustomizers) throws Exception {
        // 1. 初始化并启动 TCP 服务端
        TcpServerProperties serverProps = new TcpServerProperties();
        serverProps.setPort(0);
        serverProps.getFraming().setMode(FramingMode.DELIMITER);
        serverProps.getFraming().setDelimiter("\n");

        sessionManager = new TcpSessionManager();
        serverCorrelationManager = new TcpRequestCorrelationManager();

        server = new TcpServer(
                serverProps,
                sessionManager,
                serverCorrelationManager,
                Collections.emptyList(),
                serverCustomizers != null ? serverCustomizers : Collections.emptyList(),
                null
        );
        server.start();

        int actualPort = server.getActualPort();
        assertThat(actualPort).isGreaterThan(0);

        // 2. 初始化并启动 TCP 客户端
        TcpClientProperties clientProps = new TcpClientProperties();
        clientProps.setEnabled(true);
        clientProps.setHost("127.0.0.1");
        clientProps.setPort(actualPort);
        clientProps.getFraming().setMode(FramingMode.DELIMITER);
        clientProps.getFraming().setDelimiter("\n");
        clientProps.getReconnect().setEnabled(true);
        clientProps.getReconnect().setInitialInterval(Duration.ofMillis(100));
        clientProps.getReconnect().setMaxInterval(Duration.ofMillis(500));
        clientProps.getReconnect().setMultiplier(2.0);

        clientCorrelationManager = new TcpRequestCorrelationManager();

        client = new TcpClient(
                clientProps,
                Collections.emptyList(),
                clientCustomizers != null ? clientCustomizers : Collections.emptyList(),
                null,
                clientCorrelationManager
        );
        client.start();

        clientTemplate = new TcpClientTemplate(client, clientProps, clientCorrelationManager);
        serverTemplate = new TcpServerTemplate(sessionManager, serverCorrelationManager);

        // 等待客户端连接就绪且服务端已完成会话注册
        long deadline = System.currentTimeMillis() + 3000;
        while ((!client.isConnected() || sessionManager.getActiveCount() == 0) && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        assertThat(client.isConnected()).isTrue();
        assertThat(sessionManager.getActiveCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("验证客户端向服务端单向发送通信载荷成功")
    void testClientSendToServer() throws Exception {
        List<String> serverReceived = new CopyOnWriteArrayList<>();
        CountDownLatch latch = new CountDownLatch(1);

        TcpChannelCustomizer serverCustomizer = ch -> ch.pipeline().addLast(new SimpleChannelInboundHandler<TcpPayload>() {
            @Override
            protected void channelRead0(ChannelHandlerContext ctx, TcpPayload msg) {
                serverReceived.add(msg.asString());
                latch.countDown();
            }
        });

        initServerAndClient(List.of(serverCustomizer), Collections.emptyList());

        clientTemplate.send("Client-Ping\n");

        boolean received = latch.await(2, TimeUnit.SECONDS);
        assertThat(received).isTrue();
        assertThat(serverReceived).contains("Client-Ping");
    }

    @Test
    @DisplayName("验证客户端发起全双工 RPC request 获取服务端精准回包")
    void testClientToServerRequestResponse() throws Exception {
        TcpChannelCustomizer serverCustomizer = ch -> ch.pipeline().addLast(new SimpleChannelInboundHandler<TcpPayload>() {
            @Override
            protected void channelRead0(ChannelHandlerContext ctx, TcpPayload msg) {
                String reqJson = msg.asString();
                if (reqJson.contains("\"requestId\"")) {
                    String reqId = clientCorrelationManager.getCorrelationIdExtractor().extract(reqJson);
                    if (reqId != null) {
                        String respJson = "{\"requestId\":\"" + reqId + "\",\"result\":\"PONG_SUCCESS\"}\n";
                        ctx.writeAndFlush(respJson);
                    }
                }
            }
        });

        initServerAndClient(List.of(serverCustomizer), Collections.emptyList());

        String reqId = IdUtil.fastSimpleUUID();
        String reqBody = "{\"requestId\":\"" + reqId + "\",\"command\":\"PING\"}\n";
        TcpPacket reqPacket = TcpPacket.of("PING", reqId, TcpPayload.of(reqBody));

        TcpPacket response = clientTemplate.request(reqPacket, Duration.ofSeconds(3));

        assertThat(response).isNotNull();
        assertThat(response.getRequestId()).isEqualTo(reqId);
        assertThat(response.getPayload().asString()).contains("PONG_SUCCESS");
    }

    @Test
    @DisplayName("验证服务端发起全双工 RPC request 获取客户端精准回包")
    void testServerToClientRequestResponse() throws Exception {
        TcpChannelCustomizer clientCustomizer = ch -> ch.pipeline().addLast(new SimpleChannelInboundHandler<TcpPayload>() {
            @Override
            protected void channelRead0(ChannelHandlerContext ctx, TcpPayload msg) {
                String reqJson = msg.asString();
                if (reqJson.contains("\"requestId\"")) {
                    String reqId = serverCorrelationManager.getCorrelationIdExtractor().extract(reqJson);
                    if (reqId != null) {
                        String respJson = "{\"requestId\":\"" + reqId + "\",\"deviceStatus\":\"ONLINE_OK\"}\n";
                        ctx.writeAndFlush(respJson);
                    }
                }
            }
        });

        initServerAndClient(Collections.emptyList(), List.of(clientCustomizer));

        assertThat(sessionManager.getActiveCount()).isEqualTo(1);
        TcpSession serverSideSession = sessionManager.getAllSessions().iterator().next();

        String reqId = IdUtil.fastSimpleUUID();
        String reqBody = "{\"requestId\":\"" + reqId + "\",\"action\":\"QUERY_STATUS\"}\n";
        TcpPacket reqPacket = TcpPacket.of("QUERY", reqId, TcpPayload.of(reqBody));

        TcpPacket response = serverTemplate.request(serverSideSession.getSessionId(), reqPacket, Duration.ofSeconds(3));

        assertThat(response).isNotNull();
        assertThat(response.getRequestId()).isEqualTo(reqId);
        assertThat(response.getPayload().asString()).contains("ONLINE_OK");
    }

    @Test
    @DisplayName("验证客户端 RPC 请求超时熔断抛出 TcpTimeoutException 并清理挂起句柄杜绝泄漏")
    void testClientRequestTimeout() throws Exception {
        initServerAndClient(Collections.emptyList(), Collections.emptyList());

        String reqId = IdUtil.fastSimpleUUID();
        String reqBody = "{\"requestId\":\"" + reqId + "\",\"command\":\"NO_REPLY\"}\n";
        TcpPacket reqPacket = TcpPacket.of("PING", reqId, TcpPayload.of(reqBody));

        assertThatThrownBy(() -> clientTemplate.request(reqPacket, Duration.ofMillis(150)))
                .isInstanceOf(TcpTimeoutException.class)
                .hasMessageContaining("TCP 请求");

        assertThat(clientCorrelationManager.getPendingCount()).isEqualTo(0);
        assertThat(clientCorrelationManager.hasPendingRequests()).isFalse();
    }

    @Test
    @DisplayName("验证服务端断开后客户端触发指数退避自动重连自愈")
    void testClientExponentialBackoffReconnect() throws Exception {
        initServerAndClient(Collections.emptyList(), Collections.emptyList());
        int boundPort = server.getActualPort();

        assertThat(client.isConnected()).isTrue();

        // 停止服务端，模拟网络故障或服务端停机
        server.stop();

        // 验证客户端断链
        long waitDisconnect = System.currentTimeMillis() + 2000;
        while (client.isConnected() && System.currentTimeMillis() < waitDisconnect) {
            Thread.sleep(50);
        }
        assertThat(client.isConnected()).isFalse();

        // 重启服务端在原端口，验证客户端自愈重连
        TcpServerProperties restartProps = new TcpServerProperties();
        restartProps.setPort(boundPort);
        restartProps.getFraming().setMode(FramingMode.DELIMITER);
        restartProps.getFraming().setDelimiter("\n");

        server = new TcpServer(
                restartProps,
                sessionManager,
                serverCorrelationManager,
                Collections.emptyList(),
                Collections.emptyList(),
                null
        );
        server.start();

        // 等待客户端自愈重连成功
        long waitReconnect = System.currentTimeMillis() + 5000;
        while (!client.isConnected() && System.currentTimeMillis() < waitReconnect) {
            Thread.sleep(50);
        }
        assertThat(client.isConnected()).isTrue();
    }

    @Test
    @DisplayName("验证客户端手动 reconnect 同步重连")
    void testClientManualReconnect() throws Exception {
        initServerAndClient(Collections.emptyList(), Collections.emptyList());
        assertThat(clientTemplate.isConnected()).isTrue();

        clientTemplate.reconnect();
        assertThat(clientTemplate.isConnected()).isTrue();
    }

    @Test
    @DisplayName("验证服务端与客户端热重启 (stop -> start) 后全双工 RPC 时间轮自动可用，且并发启停重连线程安全")
    void testRestartPreservesFullDuplexRpcAndConcurrentLifecycle() throws Exception {
        TcpChannelCustomizer serverCustomizer = ch -> ch.pipeline().addLast(new SimpleChannelInboundHandler<TcpPayload>() {
            @Override
            protected void channelRead0(ChannelHandlerContext ctx, TcpPayload msg) {
                String reqJson = msg.asString();
                if (reqJson.contains("\"requestId\"")) {
                    String reqId = clientCorrelationManager.getCorrelationIdExtractor().extract(reqJson);
                    if (reqId != null) {
                        ctx.writeAndFlush("{\"requestId\":\"" + reqId + "\",\"result\":\"RESTART_RPC_OK\"}\n");
                    }
                }
            }
        });

        initServerAndClient(List.of(serverCustomizer), Collections.emptyList());

        // 1. 客户端 stop -> start 热重启后发起全双工 RPC
        client.stop();
        assertThat(client.isRunning()).isFalse();
        client.start();
        long deadlineClient = System.currentTimeMillis() + 3000;
        while (!client.isConnected() && System.currentTimeMillis() < deadlineClient) {
            Thread.sleep(30);
        }
        assertThat(client.isConnected()).isTrue();

        String reqId1 = IdUtil.fastSimpleUUID();
        TcpPacket resp1 = clientTemplate.request(
                TcpPacket.of("PING", reqId1, TcpPayload.of("{\"requestId\":\"" + reqId1 + "\",\"command\":\"PING\"}\n")),
                Duration.ofSeconds(3)
        );
        assertThat(resp1.getPayload().asString()).contains("RESTART_RPC_OK");

        // 2. 服务端 stop -> start 热重启后（同端口）客户端重连并发起全双工 RPC
        int boundPort = server.getActualPort();
        server.stop();
        assertThat(server.isRunning()).isFalse();
        server.getProperties().setPort(boundPort);
        server.start();
        assertThat(server.isRunning()).isTrue();

        client.reconnect();
        assertThat(client.isConnected()).isTrue();

        String reqId2 = IdUtil.fastSimpleUUID();
        TcpPacket resp2 = clientTemplate.request(
                TcpPacket.of("PING", reqId2, TcpPayload.of("{\"requestId\":\"" + reqId2 + "\",\"command\":\"PING\"}\n")),
                Duration.ofSeconds(3)
        );
        assertThat(resp2.getPayload().asString()).contains("RESTART_RPC_OK");

        // 3. 在 JDK 21+ 上基于虚拟线程（JDK 17 回退平台线程）并发调用 start / stop / reconnect 验证 ReentrantLock 互斥与防 Pinning 安全性
        org.springframework.core.task.SimpleAsyncTaskExecutor executor = new org.springframework.core.task.SimpleAsyncTaskExecutor("test-tcp-vt-");
        if (Runtime.version().feature() >= 21) {
            executor.setVirtualThreads(true);
        }
        try {
            java.util.concurrent.CountDownLatch startLatch = new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.CountDownLatch doneLatch = new java.util.concurrent.CountDownLatch(5);
            java.util.List<Throwable> errors = new CopyOnWriteArrayList<>();
            java.util.List<Runnable> actions = java.util.List.of(
                    () -> client.stop(),
                    () -> client.start(),
                    () -> {
                        try {
                            client.reconnect();
                        } catch (Exception ignored) {
                            // 并发 stop 窗口下 reconnect 可能抛出连接失败异常，锁互斥不应死锁或 Pinning
                        }
                    },
                    () -> client.start(),
                    () -> server.start()
            );
            for (Runnable action : actions) {
                executor.execute(() -> {
                    try {
                        startLatch.await();
                        action.run();
                    } catch (Throwable ex) {
                        errors.add(ex);
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }
            startLatch.countDown();
            assertThat(doneLatch.await(15, TimeUnit.SECONDS)).isTrue();
            assertThat(errors).isEmpty();
            client.stop();
            client.start();
            assertThat(client.isConnected()).isTrue();
        } finally {
            executor.close();
        }
    }

    @Test
    @DisplayName("验证毫秒级心跳保活（250ms）与自定义 response-payload 静默消费不穿透下游业务")
    void testMillisecondHeartbeatAndCustomResponsePayload() throws Exception {
        CountDownLatch pingReceivedLatch = new CountDownLatch(2);
        List<String> serverReceivedPings = new CopyOnWriteArrayList<>();

        TcpServerProperties serverProps = new TcpServerProperties();
        serverProps.setPort(0);
        serverProps.getFraming().setMode(FramingMode.DELIMITER);
        serverProps.getFraming().setDelimiter("\n");

        sessionManager = new TcpSessionManager();
        serverCorrelationManager = new TcpRequestCorrelationManager();
        server = new TcpServer(
                serverProps,
                sessionManager,
                serverCorrelationManager,
                Collections.emptyList(),
                List.of((TcpChannelCustomizer) ch -> ch.pipeline().addLast(new SimpleChannelInboundHandler<TcpPayload>() {
                    @Override
                    protected void channelRead0(ChannelHandlerContext ctx, TcpPayload msg) {
                        String text = msg.asString().trim();
                        if ("CUSTOM_HB_PING".equals(text)) {
                            serverReceivedPings.add(text);
                            ctx.writeAndFlush("CUSTOM_HB_PONG\n");
                            pingReceivedLatch.countDown();
                        }
                    }
                })),
                null
        );
        server.start();
        int port = server.getActualPort();

        List<String> clientDownstreamMessages = new CopyOnWriteArrayList<>();
        CountDownLatch bizMsgLatch = new CountDownLatch(1);

        TcpClientProperties clientProps = new TcpClientProperties();
        clientProps.setEnabled(true);
        clientProps.setHost("127.0.0.1");
        clientProps.setPort(port);
        clientProps.getFraming().setMode(FramingMode.DELIMITER);
        clientProps.getFraming().setDelimiter("\n");
        clientProps.getHeartbeat().setEnabled(true);
        clientProps.getHeartbeat().setInterval(Duration.ofMillis(250));
        clientProps.getHeartbeat().setPayload("CUSTOM_HB_PING");
        clientProps.getHeartbeat().setResponsePayload("CUSTOM_HB_PONG");

        clientCorrelationManager = new TcpRequestCorrelationManager();
        client = new TcpClient(
                clientProps,
                Collections.emptyList(),
                List.of((TcpChannelCustomizer) ch -> ch.pipeline().addLast(new SimpleChannelInboundHandler<TcpPayload>() {
                    @Override
                    protected void channelRead0(ChannelHandlerContext ctx, TcpPayload msg) {
                        clientDownstreamMessages.add(msg.asString().trim());
                        if ("BIZ_HELLO".equals(msg.asString().trim())) {
                            bizMsgLatch.countDown();
                        }
                    }
                })),
                null,
                clientCorrelationManager
        );
        client.start();

        // 1. 亚秒级 250ms 心跳应在 2s 内至少触发 2 次（若按旧实现 toSeconds() 截断为 0 则永不触发）
        assertThat(pingReceivedLatch.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(serverReceivedPings).hasSizeGreaterThanOrEqualTo(2);

        // 2. 向客户端发送业务报文，确认业务报文到达下游而 CUSTOM_HB_PONG 被静默消费未穿透
        serverCorrelationManager = server.getCorrelationManager();
        TcpSession serverSession = sessionManager.getAllSessions().iterator().next();
        serverSession.send("BIZ_HELLO\n");
        assertThat(bizMsgLatch.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(clientDownstreamMessages).containsExactly("BIZ_HELLO");
        assertThat(clientDownstreamMessages).doesNotContain("CUSTOM_HB_PONG");

        // 3. 验证 responsePayload 为空白时安全回退消费默认 "PONG"
        clientDownstreamMessages.clear();
        clientProps.getHeartbeat().setResponsePayload("   ");
        serverSession.send("PONG\n");
        serverSession.send("BIZ_AFTER_PONG\n");
        long waitAfterPong = System.currentTimeMillis() + 2000;
        while (!clientDownstreamMessages.contains("BIZ_AFTER_PONG") && System.currentTimeMillis() < waitAfterPong) {
            Thread.sleep(20);
        }
        assertThat(clientDownstreamMessages).contains("BIZ_AFTER_PONG");
        assertThat(clientDownstreamMessages).doesNotContain("PONG");

        // 4. 验证 JSON 载荷心跳识别：
        // 4a. 文本配置 PONG 可识别无 requestId 的 {"command":"PONG"} 心跳回执，但不误吞 {"command":"ECHO","status":"PONG"} 业务报文
        clientDownstreamMessages.clear();
        clientProps.getHeartbeat().setResponsePayload("PONG");
        serverSession.send("{\"command\":\"PONG\"}\n");
        serverSession.send("{\"command\":\"ECHO\",\"status\":\"PONG\"}\n");
        long waitJsonToken = System.currentTimeMillis() + 2000;
        while (!clientDownstreamMessages.contains("{\"command\":\"ECHO\",\"status\":\"PONG\"}") && System.currentTimeMillis() < waitJsonToken) {
            Thread.sleep(20);
        }
        assertThat(clientDownstreamMessages).contains("{\"command\":\"ECHO\",\"status\":\"PONG\"}");
        assertThat(clientDownstreamMessages).doesNotContain("{\"command\":\"PONG\"}");

        // 4b. JSON 格式 responsePayload 支持忽略键序与空白的结构化等价匹配
        clientDownstreamMessages.clear();
        clientProps.getHeartbeat().setResponsePayload("{\"type\":\"HEARTBEAT\",\"status\":\"PONG\"}");
        serverSession.send("  { \"status\" : \"PONG\" , \"type\" : \"HEARTBEAT\" }  \n");
        serverSession.send("BIZ_AFTER_JSON_HB\n");
        long waitJsonStruct = System.currentTimeMillis() + 2000;
        while (!clientDownstreamMessages.contains("BIZ_AFTER_JSON_HB") && System.currentTimeMillis() < waitJsonStruct) {
            Thread.sleep(20);
        }
        assertThat(clientDownstreamMessages).contains("BIZ_AFTER_JSON_HB");
        assertThat(clientDownstreamMessages).noneMatch(msg -> msg.contains("HEARTBEAT"));
    }
}
