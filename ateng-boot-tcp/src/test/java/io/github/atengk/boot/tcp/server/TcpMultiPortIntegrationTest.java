package io.github.atengk.boot.tcp.server;

import io.github.atengk.boot.tcp.client.TcpClient;
import io.github.atengk.boot.tcp.config.AtengTcpAutoConfiguration;
import io.github.atengk.boot.tcp.config.TcpServerAutoConfiguration;
import io.github.atengk.boot.tcp.enums.FramingMode;
import io.github.atengk.boot.tcp.model.TcpPacket;
import io.github.atengk.boot.tcp.model.TcpPayload;
import io.github.atengk.boot.tcp.properties.TcpClientProperties;
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

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * TCP 多端口多协议服务拓扑集成测试
 *
 * @author Ateng
 * @since 2026-10-26
 */
@SpringBootTest(classes = TcpMultiPortIntegrationTest.TestApp.class, properties = {
        "ateng.boot.tcp.servers.lineBased.enabled=true",
        "ateng.boot.tcp.servers.lineBased.port=0",
        "ateng.boot.tcp.servers.lineBased.framing.mode=DELIMITER",
        "ateng.boot.tcp.servers.lineBased.framing.delimiter=\\n",

        "ateng.boot.tcp.servers.fixedLength.enabled=true",
        "ateng.boot.tcp.servers.fixedLength.port=0",
        "ateng.boot.tcp.servers.fixedLength.framing.mode=FIXED_LENGTH",
        "ateng.boot.tcp.servers.fixedLength.framing.fixed-length=16"
})
class TcpMultiPortIntegrationTest {

    @Autowired
    private TcpServerRegistry serverRegistry;

    @Autowired
    private MessageCollector messageCollector;

    private TcpClient client1;
    private TcpClient client2;

    @AfterEach
    void tearDown() {
        if (client1 != null) {
            client1.stop();
        }
        if (client2 != null) {
            client2.stop();
        }
        messageCollector.clear();
    }

    @Test
    @DisplayName("多端口多协议服务端同时监听并独立收发自洽")
    void testMultiPortConcurrentListening() {
        // 1. 验证两端口独立监听
        TcpServer lineServer = serverRegistry.getRequiredServer("lineBased");
        TcpServer fixedServer = serverRegistry.getRequiredServer("fixedLength");

        assertThat(lineServer.isRunning()).isTrue();
        assertThat(fixedServer.isRunning()).isTrue();

        int port1 = lineServer.getActualPort();
        int port2 = fixedServer.getActualPort();

        assertThat(port1).isPositive();
        assertThat(port2).isPositive();
        assertThat(port1).isNotEqualTo(port2);

        // 2. 建立针对 lineBased 端口的客户端
        TcpClientProperties props1 = new TcpClientProperties();
        props1.setHost("127.0.0.1");
        props1.setPort(port1);
        props1.getFraming().setMode(FramingMode.DELIMITER);
        props1.getFraming().setDelimiter("\n");
        client1 = new TcpClient("client-line", props1);
        client1.start();
        TcpClientTemplate template1 = new TcpClientTemplate(client1, props1);

        // 3. 建立针对 fixedLength 端口的客户端
        TcpClientProperties props2 = new TcpClientProperties();
        props2.setHost("127.0.0.1");
        props2.setPort(port2);
        props2.getFraming().setMode(FramingMode.FIXED_LENGTH);
        props2.getFraming().setFixedLength(16);
        client2 = new TcpClient("client-fixed", props2);
        client2.start();
        TcpClientTemplate template2 = new TcpClientTemplate(client2, props2);

        // 4. 客户端 1 发送换行符分帧消息
        template1.send(TcpPayload.of("HELLO-LINE\n"));

        // 5. 客户端 2 发送 16 字节固定长度消息
        byte[] fixedBytes = "1234567890ABCDEF".getBytes(StandardCharsets.UTF_8);
        template2.send(TcpPayload.of(fixedBytes));

        // 6. 断言服务端均成功接收到对应格式的完整报文
        await().untilAsserted(() -> {
            assertThat(messageCollector.getReceivedTexts())
                    .anyMatch(text -> text.contains("HELLO-LINE"));
            assertThat(messageCollector.getReceivedLengths())
                    .contains(16);
        });
    }

    @Component
    static class MessageCollector implements TcpPacketFilter {
        private final List<String> receivedTexts = new CopyOnWriteArrayList<>();
        private final List<Integer> receivedLengths = new CopyOnWriteArrayList<>();

        @Override
        public boolean filterInbound(TcpPacket packet, ChannelHandlerContext ctx) {
            if (packet != null && packet.getPayload() != null) {
                TcpPayload payload = packet.getPayload();
                receivedTexts.add(payload.asString());
                receivedLengths.add(payload.getBytes().length);
            }
            return true;
        }

        public List<String> getReceivedTexts() {
            return receivedTexts;
        }

        public List<Integer> getReceivedLengths() {
            return receivedLengths;
        }

        public void clear() {
            receivedTexts.clear();
            receivedLengths.clear();
        }
    }

    @SpringBootApplication(scanBasePackageClasses = {
            AtengTcpAutoConfiguration.class,
            TcpServerAutoConfiguration.class
    })
    @Import(MessageCollector.class)
    static class TestApp {
    }
}
