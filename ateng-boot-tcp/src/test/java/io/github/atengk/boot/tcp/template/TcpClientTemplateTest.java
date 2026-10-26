package io.github.atengk.boot.tcp.template;

import io.github.atengk.boot.tcp.client.TcpClient;
import io.github.atengk.boot.tcp.correlation.TcpRequestCorrelationManager;
import io.github.atengk.boot.tcp.exception.TcpTimeoutException;
import io.github.atengk.boot.tcp.model.TcpPacket;
import io.github.atengk.boot.tcp.model.TcpPayload;
import io.github.atengk.boot.tcp.properties.TcpClientProperties;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Collections;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

/**
 * TCP 客户端核心操作门面单元测试
 *
 * @author Ateng
 * @since 2026-10-26
 */
@DisplayName("TCP 客户端核心操作门面单元测试")
class TcpClientTemplateTest {

    private TcpClient tcpClient;
    private TcpClientProperties properties;
    private TcpRequestCorrelationManager correlationManager;
    private TcpClientTemplate clientTemplate;
    private EmbeddedChannel embeddedChannel;

    @BeforeEach
    void setUp() {
        properties = new TcpClientProperties();
        properties.setRequestTimeout(Duration.ofSeconds(2));
        correlationManager = new TcpRequestCorrelationManager();

        tcpClient = mock(TcpClient.class);
        embeddedChannel = new EmbeddedChannel();

        when(tcpClient.isConnected()).thenReturn(true);
        when(tcpClient.getChannel()).thenReturn(embeddedChannel);
        when(tcpClient.getProperties()).thenReturn(properties);
        when(tcpClient.getCorrelationManager()).thenReturn(correlationManager);

        clientTemplate = new TcpClientTemplate(tcpClient, properties, correlationManager);
    }

    @AfterEach
    void tearDown() {
        if (correlationManager != null) {
            correlationManager.close();
        }
        if (embeddedChannel != null && embeddedChannel.isOpen()) {
            embeddedChannel.finishAndReleaseAll();
        }
    }

    @Test
    @DisplayName("验证已连接状态下正常发送单向消息")
    void testSendSuccess() {
        clientTemplate.send("Client Hello");

        Object outbound = embeddedChannel.readOutbound();
        assertThat(outbound).isEqualTo("Client Hello");
    }

    @Test
    @DisplayName("验证未连接状态下发送消息抛出 IllegalStateException")
    void testSendWhenDisconnected() {
        when(tcpClient.isConnected()).thenReturn(false);

        assertThatThrownBy(() -> clientTemplate.send("Hello"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("尚未建立活跃连接");
    }

    @Test
    @DisplayName("验证全双工 RPC requestAsync 与同步 request 成功获取对端回包")
    void testRequestSuccess() throws Exception {
        CompletableFuture<TcpPacket> future = clientTemplate.requestAsync("Query Sensor");

        Object outbound = embeddedChannel.readOutbound();
        assertThat(outbound).isInstanceOf(TcpPacket.class);
        TcpPacket packet = (TcpPacket) outbound;
        assertThat(packet.getRequestId()).isNotNull();

        // 模拟对端返回关联回包
        TcpPacket resp = TcpPacket.of("RESULT", packet.getRequestId(), TcpPayload.of("{\"temp\":26.5}"));
        correlationManager.complete(packet.getRequestId(), resp);

        TcpPacket result = future.get(1, TimeUnit.SECONDS);
        assertThat(result.getRequestId()).isEqualTo(packet.getRequestId());
        assertThat(result.getPayload().asString()).contains("26.5");
    }

    @Test
    @DisplayName("验证同步 request 在超时未收到对端回包时抛出 TcpTimeoutException")
    void testRequestTimeout() {
        assertThatThrownBy(() -> clientTemplate.request("Query Timeout", Duration.ofMillis(100)))
                .isInstanceOf(TcpTimeoutException.class)
                .hasMessageContaining("TCP 请求");
    }

    @Test
    @DisplayName("验证探活与控制门面委托")
    void testControlDelegation() {
        assertThat(clientTemplate.isConnected()).isTrue();
        assertThat(clientTemplate.getChannel()).isSameAs(embeddedChannel);
        assertThat(clientTemplate.getClient()).isSameAs(tcpClient);
        assertThat(clientTemplate.getCorrelationManager()).isSameAs(correlationManager);

        clientTemplate.reconnect();
        verify(tcpClient, times(1)).reconnect();

        clientTemplate.disconnect();
        verify(tcpClient, times(1)).disconnect();
    }
}
