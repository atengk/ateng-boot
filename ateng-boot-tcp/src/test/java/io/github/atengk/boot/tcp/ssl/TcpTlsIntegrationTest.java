package io.github.atengk.boot.tcp.ssl;

import io.github.atengk.boot.tcp.client.TcpClient;
import io.github.atengk.boot.tcp.enums.ClientAuth;
import io.github.atengk.boot.tcp.enums.FramingMode;
import io.github.atengk.boot.tcp.model.TcpPacket;
import io.github.atengk.boot.tcp.model.TcpPayload;
import io.github.atengk.boot.tcp.properties.TcpClientProperties;
import io.github.atengk.boot.tcp.properties.TcpServerProperties;
import io.github.atengk.boot.tcp.server.TcpServer;
import io.github.atengk.boot.tcp.session.TcpSessionManager;
import io.github.atengk.boot.tcp.spi.TcpChannelCustomizer;
import io.github.atengk.boot.tcp.spi.TcpPacketFilter;
import io.github.atengk.boot.tcp.template.TcpClientTemplate;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.GenericApplicationContext;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/**
 * TCP TLS/mTLS 链路加密与双向证书强认证集成测试
 *
 * @author Ateng
 * @since 2026-10-26
 */
class TcpTlsIntegrationTest {

    private TcpServer server;
    private TcpClient client;

    @AfterEach
    void tearDown() {
        if (client != null) {
            client.stop();
        }
        if (server != null) {
            server.stop();
        }
    }

    @Test
    @DisplayName("单向 TLS 传输加密：客户端验证服务端自签名证书成功通信")
    void testOneWayTlsSuccess() {
        List<String> receivedServerTexts = new CopyOnWriteArrayList<>();

        // 1. 服务端配置单向 TLS
        TcpServerProperties serverProps = new TcpServerProperties();
        serverProps.setHost("127.0.0.1");
        serverProps.setPort(0);
        serverProps.getFraming().setMode(FramingMode.DELIMITER);
        serverProps.getFraming().setDelimiter("\n");
        serverProps.getSsl().setEnabled(true);
        serverProps.getSsl().setKeyStore("classpath:ssl/server.p12");
        serverProps.getSsl().setKeyStorePassword("123456");
        serverProps.getSsl().setClientAuth(ClientAuth.NONE);

        TcpPacketFilter serverFilter = new TcpPacketFilter() {
            @Override
            public boolean filterInbound(TcpPacket packet, ChannelHandlerContext ctx) {
                if (packet != null && packet.getPayload() != null) {
                    receivedServerTexts.add(packet.getPayload().asString());
                }
                return true;
            }
        };

        server = new TcpServer("tlsServer", serverProps, new TcpSessionManager(), null, null,
                List.of(serverFilter), Collections.emptyList(), new GenericApplicationContext());
        server.start();

        int port = server.getActualPort();

        // 2. 客户端配置信任服务端的 client-truststore.p12
        TcpClientProperties clientProps = new TcpClientProperties();
        clientProps.setHost("127.0.0.1");
        clientProps.setPort(port);
        clientProps.getFraming().setMode(FramingMode.DELIMITER);
        clientProps.getFraming().setDelimiter("\n");
        clientProps.getSsl().setEnabled(true);
        clientProps.getSsl().setTrustStore("classpath:ssl/client-truststore.p12");
        clientProps.getSsl().setTrustStorePassword("123456");

        client = new TcpClient("tlsClient", clientProps);
        client.start();

        await().until(client::isConnected);

        TcpClientTemplate template = new TcpClientTemplate(client, clientProps);
        template.send(TcpPayload.of("TLS_SECURE_PAYLOAD\n"));

        await().untilAsserted(() -> assertThat(receivedServerTexts).anyMatch(t -> t.contains("TLS_SECURE_PAYLOAD")));
    }

    @Test
    @DisplayName("双向 mTLS 证书强认证：合法客户端证书握手成功通信")
    void testMtlsAuthorizedSuccess() {
        List<String> receivedServerTexts = new CopyOnWriteArrayList<>();

        // 1. 服务端开启双向认证 NEED 并加载 server-truststore.p12
        TcpServerProperties serverProps = new TcpServerProperties();
        serverProps.setHost("127.0.0.1");
        serverProps.setPort(0);
        serverProps.getFraming().setMode(FramingMode.DELIMITER);
        serverProps.getFraming().setDelimiter("\n");
        serverProps.getSsl().setEnabled(true);
        serverProps.getSsl().setKeyStore("classpath:ssl/server.p12");
        serverProps.getSsl().setKeyStorePassword("123456");
        serverProps.getSsl().setTrustStore("classpath:ssl/server-truststore.p12");
        serverProps.getSsl().setTrustStorePassword("123456");
        serverProps.getSsl().setClientAuth(ClientAuth.NEED);

        TcpPacketFilter serverFilter = new TcpPacketFilter() {
            @Override
            public boolean filterInbound(TcpPacket packet, ChannelHandlerContext ctx) {
                if (packet != null && packet.getPayload() != null) {
                    receivedServerTexts.add(packet.getPayload().asString());
                }
                return true;
            }
        };

        server = new TcpServer("mtlsServer", serverProps, new TcpSessionManager(), null, null,
                List.of(serverFilter), Collections.emptyList(), new GenericApplicationContext());
        server.start();

        int port = server.getActualPort();

        // 2. 合法客户端：携带 client.p12 与 client-truststore.p12
        TcpClientProperties clientProps = new TcpClientProperties();
        clientProps.setHost("127.0.0.1");
        clientProps.setPort(port);
        clientProps.getFraming().setMode(FramingMode.DELIMITER);
        clientProps.getFraming().setDelimiter("\n");
        clientProps.getSsl().setEnabled(true);
        clientProps.getSsl().setKeyStore("classpath:ssl/client.p12");
        clientProps.getSsl().setKeyStorePassword("123456");
        clientProps.getSsl().setTrustStore("classpath:ssl/client-truststore.p12");
        clientProps.getSsl().setTrustStorePassword("123456");

        client = new TcpClient("mtlsClient", clientProps);
        client.start();

        await().until(client::isConnected);

        TcpClientTemplate template = new TcpClientTemplate(client, clientProps);
        template.send(TcpPayload.of("MTLS_AUTHORIZED_PAYLOAD\n"));

        await().untilAsserted(() -> assertThat(receivedServerTexts).anyMatch(t -> t.contains("MTLS_AUTHORIZED_PAYLOAD")));
    }

    @Test
    @DisplayName("双向 mTLS 强防卫：非法不受信任客户端证书握手失败拒绝连接")
    void testMtlsUnauthorizedRejected() {
        // 1. 服务端开启双向认证 NEED
        TcpServerProperties serverProps = new TcpServerProperties();
        serverProps.setHost("127.0.0.1");
        serverProps.setPort(0);
        serverProps.getSsl().setEnabled(true);
        serverProps.getSsl().setKeyStore("classpath:ssl/server.p12");
        serverProps.getSsl().setKeyStorePassword("123456");
        serverProps.getSsl().setTrustStore("classpath:ssl/server-truststore.p12");
        serverProps.getSsl().setTrustStorePassword("123456");
        serverProps.getSsl().setClientAuth(ClientAuth.NEED);

        server = new TcpServer("mtlsGuardServer", serverProps, new TcpSessionManager(), null, null,
                Collections.emptyList(), Collections.emptyList(), new GenericApplicationContext());
        server.start();

        int port = server.getActualPort();

        // 2. 非法客户端：携带服务端信任库中不存在的 untrusted-client.p12，关闭重连
        TcpClientProperties clientProps = new TcpClientProperties();
        clientProps.setHost("127.0.0.1");
        clientProps.setPort(port);
        clientProps.getReconnect().setEnabled(false);
        clientProps.getSsl().setEnabled(true);
        clientProps.getSsl().setKeyStore("classpath:ssl/untrusted-client.p12");
        clientProps.getSsl().setKeyStorePassword("123456");
        clientProps.getSsl().setTrustStore("classpath:ssl/client-truststore.p12");
        clientProps.getSsl().setTrustStorePassword("123456");

        client = new TcpClient("untrustedClient", clientProps);

        // 3. 验证客户端连接时由于证书被服务端拒绝，握手异常断开或无法维持连接
        client.start();
        await().untilAsserted(() -> assertThat(client.isConnected()).isFalse());
    }

    @Test
    @DisplayName("TcpChannelCustomizer SPI 逃生舱精准回调与角色感知")
    void testChannelCustomizerSpi() {
        List<String> customizedLogs = new CopyOnWriteArrayList<>();
        TcpChannelCustomizer customizer = new TcpChannelCustomizer() {
            @Override
            public void customize(Channel channel) {
            }

            @Override
            public void customize(Channel channel, String role, String name) {
                customizedLogs.add(role + ":" + name);
            }
        };

        TcpServerProperties serverProps = new TcpServerProperties();
        serverProps.setHost("127.0.0.1");
        serverProps.setPort(0);

        server = new TcpServer("customServer", serverProps, new TcpSessionManager(), null, null,
                Collections.emptyList(), List.of(customizer), new GenericApplicationContext());
        server.start();

        TcpClientProperties clientProps = new TcpClientProperties();
        clientProps.setHost("127.0.0.1");
        clientProps.setPort(server.getActualPort());

        client = new TcpClient("customClient", clientProps, Collections.emptyList(), List.of(customizer),
                null, null, null);
        client.start();

        await().untilAsserted(() -> {
            assertThat(customizedLogs).contains("SERVER:customServer", "CLIENT:customClient");
        });
    }
}
