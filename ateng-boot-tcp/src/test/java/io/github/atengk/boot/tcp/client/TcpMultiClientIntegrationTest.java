package io.github.atengk.boot.tcp.client;

import io.github.atengk.boot.tcp.config.AtengTcpAutoConfiguration;
import io.github.atengk.boot.tcp.config.TcpClientAutoConfiguration;
import io.github.atengk.boot.tcp.config.TcpServerAutoConfiguration;
import io.github.atengk.boot.tcp.model.TcpPacket;
import io.github.atengk.boot.tcp.model.TcpPayload;
import io.github.atengk.boot.tcp.server.TcpServer;
import io.github.atengk.boot.tcp.spi.TcpPacketFilter;
import io.github.atengk.boot.tcp.template.TcpClientTemplate;
import io.netty.channel.ChannelHandlerContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.stereotype.Component;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * 命名多客户端注册表与并发长连接集成测试
 *
 * @author Ateng
 * @since 2026-10-26
 */
@SpringBootTest(classes = TcpMultiClientIntegrationTest.TestApp.class, properties = {
        "ateng.boot.tcp.server.enabled=true",
        "ateng.boot.tcp.server.framing.mode=DELIMITER",
        "ateng.boot.tcp.server.framing.delimiter=\\n",

        "ateng.boot.tcp.clients.partnerA.enabled=true",
        "ateng.boot.tcp.clients.partnerA.framing.mode=DELIMITER",
        "ateng.boot.tcp.clients.partnerA.framing.delimiter=\\n",

        "ateng.boot.tcp.clients.partnerB.enabled=true",
        "ateng.boot.tcp.clients.partnerB.framing.mode=DELIMITER",
        "ateng.boot.tcp.clients.partnerB.framing.delimiter=\\n"
})
class TcpMultiClientIntegrationTest {

    private static final int SERVER_PORT = findFreePort();

    @DynamicPropertySource
    static void dynamicProperties(DynamicPropertyRegistry registry) {
        registry.add("ateng.boot.tcp.server.port", () -> SERVER_PORT);
        registry.add("ateng.boot.tcp.clients.partnerA.port", () -> SERVER_PORT);
        registry.add("ateng.boot.tcp.clients.partnerB.port", () -> SERVER_PORT);
    }

    private static int findFreePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            socket.setReuseAddress(true);
            return socket.getLocalPort();
        } catch (IOException e) {
            return 8989;
        }
    }

    @Autowired
    private TcpClientRegistry clientRegistry;

    @Autowired
    private TcpServer tcpServer;

    @Autowired
    private ServerPacketCollector packetCollector;

    @AfterEach
    void tearDown() {
        packetCollector.clear();
    }

    @Test
    @DisplayName("命名多客户端独立注册并能同时向服务端发送消息")
    void testMultiClientIndependentCommunication() {
        assertThat(tcpServer.isRunning()).isTrue();

        // 1. 验证 partnerA 与 partnerB 均被客户端注册表纳管
        TcpClient clientA = clientRegistry.getRequiredClient("partnerA");
        TcpClient clientB = clientRegistry.getRequiredClient("partnerB");

        assertThat(clientA).isNotNull();
        assertThat(clientB).isNotNull();
        assertThat(clientA.getName()).isEqualTo("partnerA");
        assertThat(clientB.getName()).isEqualTo("partnerB");

        // 2. 获取各自的专属操作门面
        TcpClientTemplate templateA = clientRegistry.getRequiredTemplate("partnerA");
        TcpClientTemplate templateB = clientRegistry.getRequiredTemplate("partnerB");

        assertThat(templateA).isNotNull();
        assertThat(templateB).isNotNull();

        // 3. 确保两客户端均已成功连上服务端
        await().until(clientA::isConnected);
        await().until(clientB::isConnected);

        // 4. 两客户端分别发送业务报文
        templateA.send(TcpPayload.of("MSG_FROM_PARTNER_A\n"));
        templateB.send(TcpPayload.of("MSG_FROM_PARTNER_B\n"));

        // 5. 服务端通过报文过滤器验证收到了来自两个不同客户端的报文
        await().untilAsserted(() -> {
            assertThat(packetCollector.getReceivedTexts())
                    .anyMatch(t -> t.contains("MSG_FROM_PARTNER_A"))
                    .anyMatch(t -> t.contains("MSG_FROM_PARTNER_B"));
        });
    }

    @Component
    static class ServerPacketCollector implements TcpPacketFilter {
        private final List<String> receivedTexts = new CopyOnWriteArrayList<>();

        @Override
        public boolean filterInbound(TcpPacket packet, ChannelHandlerContext ctx) {
            if (packet != null && packet.getPayload() != null) {
                receivedTexts.add(packet.getPayload().asString());
            }
            return true;
        }

        public List<String> getReceivedTexts() {
            return receivedTexts;
        }

        public void clear() {
            receivedTexts.clear();
        }
    }

    @SpringBootApplication(scanBasePackageClasses = {
            AtengTcpAutoConfiguration.class,
            TcpServerAutoConfiguration.class,
            TcpClientAutoConfiguration.class
    })
    @Import(ServerPacketCollector.class)
    static class TestApp {
    }
}
