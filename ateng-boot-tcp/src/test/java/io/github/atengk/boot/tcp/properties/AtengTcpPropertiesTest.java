package io.github.atengk.boot.tcp.properties;

import io.github.atengk.boot.tcp.enums.ClientAuth;
import io.github.atengk.boot.tcp.enums.FramingMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySource;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.util.unit.DataSize;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TCP 配置属性单元测试
 *
 * @author Ateng
 * @since 2026-10-26
 */
@DisplayName("TCP 配置属性单元测试")
class AtengTcpPropertiesTest {

    @Test
    @DisplayName("验证默认配置属性与生产合理初始值")
    void testDefaultValues() {
        AtengTcpProperties properties = new AtengTcpProperties();

        assertThat(properties.isEnabled()).isTrue();

        // Server 默认值验证
        TcpServerProperties server = properties.getServer();
        assertThat(server.isEnabled()).isFalse();
        assertThat(server.getHost()).isEqualTo("0.0.0.0");
        assertThat(server.getPort()).isEqualTo(8088);
        assertThat(server.getBossThreads()).isEqualTo(1);
        assertThat(server.getWorkerThreads()).isEqualTo(0);
        assertThat(server.getMaxConnections()).isEqualTo(10000);
        assertThat(server.getDrainTimeout()).isEqualTo(Duration.ofSeconds(5));
        assertThat(server.getSoBacklog()).isEqualTo(1024);
        assertThat(server.isTcpNodelay()).isTrue();
        assertThat(server.isSoKeepalive()).isTrue();

        // Server Framing 默认值验证
        TcpFramingProperties serverFraming = server.getFraming();
        assertThat(serverFraming.getMode()).isEqualTo(FramingMode.NONE);
        assertThat(serverFraming.getDelimiter()).isEqualTo("\r\n");
        assertThat(serverFraming.getFixedLength()).isEqualTo(128);
        assertThat(serverFraming.getMaxFrameLength()).isEqualTo(DataSize.ofKilobytes(64));
        assertThat(serverFraming.getLengthField().getOffset()).isEqualTo(0);
        assertThat(serverFraming.getLengthField().getLength()).isEqualTo(4);
        assertThat(serverFraming.getLengthField().getAdjustment()).isEqualTo(0);
        assertThat(serverFraming.getLengthField().getInitialBytesToStrip()).isEqualTo(4);

        // Server Idle 默认值验证
        TcpIdleProperties serverIdle = server.getIdle();
        assertThat(serverIdle.getReaderIdleTime()).isEqualTo(Duration.ofSeconds(60));
        assertThat(serverIdle.getWriterIdleTime()).isEqualTo(Duration.ZERO);
        assertThat(serverIdle.getAllIdleTime()).isEqualTo(Duration.ZERO);
        assertThat(serverIdle.isAutoClose()).isTrue();

        // Server SSL 默认值验证
        TcpSslProperties serverSsl = server.getSsl();
        assertThat(serverSsl.isEnabled()).isFalse();
        assertThat(serverSsl.getKeyStoreType()).isEqualTo("PKCS12");
        assertThat(serverSsl.getClientAuth()).isEqualTo(ClientAuth.NONE);

        // Client 默认值验证
        TcpClientProperties client = properties.getClient();
        assertThat(client.isEnabled()).isFalse();
        assertThat(client.getHost()).isEqualTo("127.0.0.1");
        assertThat(client.getPort()).isEqualTo(8088);
        assertThat(client.getConnectTimeout()).isEqualTo(Duration.ofSeconds(5));
        assertThat(client.getRequestTimeout()).isEqualTo(Duration.ofSeconds(10));
        assertThat(client.isTcpNodelay()).isTrue();
        assertThat(client.isSoKeepalive()).isTrue();

        // Client Reconnect 默认值验证
        TcpReconnectProperties reconnect = client.getReconnect();
        assertThat(reconnect.isEnabled()).isTrue();
        assertThat(reconnect.getInitialInterval()).isEqualTo(Duration.ofSeconds(1));
        assertThat(reconnect.getMaxInterval()).isEqualTo(Duration.ofSeconds(30));
        assertThat(reconnect.getMultiplier()).isEqualTo(2.0);
        assertThat(reconnect.getMaxAttempts()).isEqualTo(0);

        // Client Heartbeat 默认值验证
        TcpHeartbeatProperties heartbeat = client.getHeartbeat();
        assertThat(heartbeat.isEnabled()).isFalse();
        assertThat(heartbeat.getInterval()).isEqualTo(Duration.ofSeconds(30));
        assertThat(heartbeat.getPayload()).isEqualTo("PING");

        // Cluster 默认值验证
        assertThat(properties.getCluster().getRedis().isEnabled()).isFalse();
        assertThat(properties.getCluster().getRedis().getTopic()).isEqualTo("ateng:tcp:cluster:broadcast");

        // Multi-instance 集合初始为空
        assertThat(properties.getServers()).isEmpty();
        assertThat(properties.getClients()).isEmpty();
    }

    @Test
    @DisplayName("验证外部属性绑定与强类型 Duration / DataSize 解析")
    void testPropertiesBinding() {
        Map<String, Object> map = new HashMap<>();
        map.put("ateng.boot.tcp.enabled", "true");
        map.put("ateng.boot.tcp.server.enabled", "true");
        map.put("ateng.boot.tcp.server.host", "192.168.1.100");
        map.put("ateng.boot.tcp.server.port", "9090");
        map.put("ateng.boot.tcp.server.max-connections", "5000");
        map.put("ateng.boot.tcp.server.drain-timeout", "10s");
        map.put("ateng.boot.tcp.server.framing.mode", "LENGTH_FIELD");
        map.put("ateng.boot.tcp.server.framing.max-frame-length", "1MB");
        map.put("ateng.boot.tcp.server.framing.length-field.offset", "2");
        map.put("ateng.boot.tcp.server.framing.length-field.length", "4");
        map.put("ateng.boot.tcp.server.ssl.enabled", "true");
        map.put("ateng.boot.tcp.server.ssl.client-auth", "NEED");
        map.put("ateng.boot.tcp.client.enabled", "true");
        map.put("ateng.boot.tcp.client.request-timeout", "15s");
        map.put("ateng.boot.tcp.client.reconnect.initial-interval", "2s");
        map.put("ateng.boot.tcp.client.reconnect.max-attempts", "5");

        // 绑定多命名实例
        map.put("ateng.boot.tcp.servers.jt808.port", "8088");
        map.put("ateng.boot.tcp.servers.jt808.framing.mode", "DELIMITER");
        map.put("ateng.boot.tcp.servers.jt808.framing.delimiter", "0x7e");

        map.put("ateng.boot.tcp.clients.partner.host", "10.0.0.1");
        map.put("ateng.boot.tcp.clients.partner.port", "9999");

        ConfigurationPropertySource source = new MapConfigurationPropertySource(map);
        Binder binder = new Binder(source);

        AtengTcpProperties properties = binder.bind("ateng.boot.tcp", Bindable.of(AtengTcpProperties.class)).get();

        assertThat(properties.getServer().isEnabled()).isTrue();
        assertThat(properties.getServer().getHost()).isEqualTo("192.168.1.100");
        assertThat(properties.getServer().getPort()).isEqualTo(9090);
        assertThat(properties.getServer().getMaxConnections()).isEqualTo(5000);
        assertThat(properties.getServer().getDrainTimeout()).isEqualTo(Duration.ofSeconds(10));
        assertThat(properties.getServer().getFraming().getMode()).isEqualTo(FramingMode.LENGTH_FIELD);
        assertThat(properties.getServer().getFraming().getMaxFrameLength()).isEqualTo(DataSize.ofMegabytes(1));
        assertThat(properties.getServer().getFraming().getLengthField().getOffset()).isEqualTo(2);
        assertThat(properties.getServer().getSsl().isEnabled()).isTrue();
        assertThat(properties.getServer().getSsl().getClientAuth()).isEqualTo(ClientAuth.NEED);

        assertThat(properties.getClient().isEnabled()).isTrue();
        assertThat(properties.getClient().getRequestTimeout()).isEqualTo(Duration.ofSeconds(15));
        assertThat(properties.getClient().getReconnect().getInitialInterval()).isEqualTo(Duration.ofSeconds(2));
        assertThat(properties.getClient().getReconnect().getMaxAttempts()).isEqualTo(5);

        // 验证多实例绑定
        assertThat(properties.getServers()).containsKey("jt808");
        assertThat(properties.getServers().get("jt808").getPort()).isEqualTo(8088);
        assertThat(properties.getServers().get("jt808").getFraming().getMode()).isEqualTo(FramingMode.DELIMITER);

        assertThat(properties.getClients()).containsKey("partner");
        assertThat(properties.getClients().get("partner").getHost()).isEqualTo("10.0.0.1");
        assertThat(properties.getClients().get("partner").getPort()).isEqualTo(9999);
    }
}
