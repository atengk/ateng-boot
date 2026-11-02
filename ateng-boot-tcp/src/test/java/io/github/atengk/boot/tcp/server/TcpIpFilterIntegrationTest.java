package io.github.atengk.boot.tcp.server;

import io.github.atengk.boot.tcp.event.TcpIpBlockedEvent;
import io.github.atengk.boot.tcp.properties.TcpIpFilterProperties;
import io.github.atengk.boot.tcp.properties.TcpServerProperties;
import io.github.atengk.boot.tcp.session.TcpSessionManager;
import io.netty.handler.ipfilter.IpFilterRuleType;
import io.netty.handler.ipfilter.IpSubnetFilterRule;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/**
 * TCP 网络层 IP 访问控制防火墙端到端集成测试
 *
 * @author Ateng
 * @since 2026-11-02
 */
@DisplayName("TCP 网络层 IP 访问控制防火墙集成测试")
class TcpIpFilterIntegrationTest {

    private TcpServer tcpServer;
    private TcpSessionManager sessionManager;
    private final List<Object> publishedEvents = new CopyOnWriteArrayList<>();

    @AfterEach
    void tearDown() {
        if (tcpServer != null && tcpServer.isRunning()) {
            tcpServer.stop();
        }
    }

    private void startServer(TcpIpFilterProperties filterProperties) {
        publishedEvents.clear();
        sessionManager = new TcpSessionManager();
        sessionManager.setApplicationEventPublisher(publishedEvents::add);

        TcpServerProperties serverProperties = new TcpServerProperties();
        serverProperties.setHost("127.0.0.1");
        serverProperties.setPort(0);
        serverProperties.setIpFilter(filterProperties);

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
    @DisplayName("验证白名单严控模式: 白名单内客户端成功建立连接并接入会话管理器")
    void testWhitelistStrictModeAcceptsWhitelistedIp() throws Exception {
        TcpIpFilterProperties filterProperties = new TcpIpFilterProperties();
        filterProperties.setEnabled(true);
        filterProperties.getWhitelist().add("127.0.0.1");

        startServer(filterProperties);
        int port = tcpServer.getActualPort();

        try (Socket clientSocket = new Socket("127.0.0.1", port)) {
            await().atMost(3, TimeUnit.SECONDS).until(() -> !sessionManager.getAllSessions().isEmpty());
            assertThat(sessionManager.getAllSessions()).hasSize(1);
            assertThat(publishedEvents.stream().anyMatch(e -> e instanceof TcpIpBlockedEvent)).isFalse();
        }
    }

    @Test
    @DisplayName("验证白名单严控模式: 非白名单客户端在握手前置首位强行拒绝并发布 TcpIpBlockedEvent")
    void testWhitelistStrictModeRejectsNonWhitelistedIp() throws Exception {
        TcpIpFilterProperties filterProperties = new TcpIpFilterProperties();
        filterProperties.setEnabled(true);
        // 配置不包含 127.0.0.1 的白名单
        filterProperties.getWhitelist().add("192.168.99.1");

        startServer(filterProperties);
        int port = tcpServer.getActualPort();

        try (Socket clientSocket = new Socket("127.0.0.1", port)) {
            clientSocket.setSoTimeout(2000);
            InputStream in = clientSocket.getInputStream();
            int readByte = -1;
            try {
                readByte = in.read();
            } catch (Exception ignored) {
            }
            assertThat(readByte).isEqualTo(-1);
        }

        // 验证捕获 TcpIpBlockedEvent 领域事件
        await().atMost(3, TimeUnit.SECONDS).until(() ->
                publishedEvents.stream().anyMatch(e -> e instanceof TcpIpBlockedEvent)
        );

        TcpIpBlockedEvent blockedEvent = publishedEvents.stream()
                .filter(e -> e instanceof TcpIpBlockedEvent)
                .map(e -> (TcpIpBlockedEvent) e)
                .findFirst()
                .orElseThrow();

        assertThat(blockedEvent.getRemoteIp()).isEqualTo("127.0.0.1");
        assertThat(blockedEvent.getRemoteAddress()).isNotNull();
        assertThat(blockedEvent.getRuleDetail()).contains("NOT_IN_WHITELIST");
        assertThat(sessionManager.getAllSessions()).isEmpty();
    }

    @Test
    @DisplayName("验证黑名单拦截模式: 命中黑名单的客户端在握手前置首位被强行阻断并发布 TcpIpBlockedEvent")
    void testBlacklistRejection() throws Exception {
        TcpIpFilterProperties filterProperties = new TcpIpFilterProperties();
        filterProperties.setEnabled(true);
        // 白名单为空，启用黑名单拦截
        filterProperties.getBlacklist().add("127.0.0.1");

        startServer(filterProperties);
        int port = tcpServer.getActualPort();

        try (Socket clientSocket = new Socket("127.0.0.1", port)) {
            clientSocket.setSoTimeout(2000);
            InputStream in = clientSocket.getInputStream();
            int readByte = -1;
            try {
                readByte = in.read();
            } catch (Exception ignored) {
            }
            assertThat(readByte).isEqualTo(-1);
        }

        await().atMost(3, TimeUnit.SECONDS).until(() ->
                publishedEvents.stream().anyMatch(e -> e instanceof TcpIpBlockedEvent)
        );

        TcpIpBlockedEvent blockedEvent = publishedEvents.stream()
                .filter(e -> e instanceof TcpIpBlockedEvent)
                .map(e -> (TcpIpBlockedEvent) e)
                .findFirst()
                .orElseThrow();

        assertThat(blockedEvent.getRemoteIp()).isEqualTo("127.0.0.1");
        assertThat(blockedEvent.getRuleDetail()).contains("MATCH_BLACKLIST: 127.0.0.1");
        assertThat(sessionManager.getAllSessions()).isEmpty();
    }

    @Test
    @DisplayName("验证 CIDR 子网掩码匹配: 127.0.0.0/8 子网白名单放行与黑名单阻断")
    void testCidrSubnetMaskEvaluation() throws Exception {
        // 1. CIDR 白名单放行
        TcpIpFilterProperties whitelistProps = new TcpIpFilterProperties();
        whitelistProps.setEnabled(true);
        whitelistProps.getWhitelist().add("127.0.0.0/8");

        startServer(whitelistProps);
        int port = tcpServer.getActualPort();

        try (Socket clientSocket = new Socket("127.0.0.1", port)) {
            await().atMost(3, TimeUnit.SECONDS).until(() -> !sessionManager.getAllSessions().isEmpty());
            assertThat(sessionManager.getAllSessions()).hasSize(1);
        }

        tcpServer.stop();

        // 2. CIDR 黑名单阻断
        TcpIpFilterProperties blacklistProps = new TcpIpFilterProperties();
        blacklistProps.setEnabled(true);
        blacklistProps.getBlacklist().add("127.0.0.0/8");

        startServer(blacklistProps);
        int newPort = tcpServer.getActualPort();

        try (Socket clientSocket = new Socket("127.0.0.1", newPort)) {
            clientSocket.setSoTimeout(2000);
            try {
                clientSocket.getInputStream().read();
            } catch (Exception ignored) {
            }
        }

        await().atMost(3, TimeUnit.SECONDS).until(() ->
                publishedEvents.stream().anyMatch(e -> e instanceof TcpIpBlockedEvent)
        );

        TcpIpBlockedEvent blockedEvent = publishedEvents.stream()
                .filter(e -> e instanceof TcpIpBlockedEvent)
                .map(e -> (TcpIpBlockedEvent) e)
                .findFirst()
                .orElseThrow();

        assertThat(blockedEvent.getRuleDetail()).contains("MATCH_BLACKLIST: 127.0.0.0/8");
        assertThat(sessionManager.getAllSessions()).isEmpty();
    }

    @Test
    @DisplayName("验证 IP 防火墙禁用时放行所有连接")
    void testDisabledFilterAllowsAll() throws Exception {
        TcpIpFilterProperties filterProperties = new TcpIpFilterProperties();
        filterProperties.setEnabled(false);
        filterProperties.getBlacklist().add("127.0.0.1");

        startServer(filterProperties);
        int port = tcpServer.getActualPort();

        try (Socket clientSocket = new Socket("127.0.0.1", port)) {
            await().atMost(3, TimeUnit.SECONDS).until(() -> !sessionManager.getAllSessions().isEmpty());
            assertThat(sessionManager.getAllSessions()).hasSize(1);
            assertThat(publishedEvents.stream().anyMatch(e -> e instanceof TcpIpBlockedEvent)).isFalse();
        }
    }

    @Test
    @DisplayName("验证 parseRule 解析单一 IP 与 CIDR 规则的正确性及异常防卫")
    void testParseRuleLogic() {
        // 单一 IPv4
        IpSubnetFilterRule v4Rule = TcpIpFilterHandler.parseRule("192.168.1.100", IpFilterRuleType.ACCEPT);
        assertThat(v4Rule.matches(new InetSocketAddress("192.168.1.100", 8080))).isTrue();
        assertThat(v4Rule.matches(new InetSocketAddress("192.168.1.101", 8080))).isFalse();

        // CIDR IPv4
        IpSubnetFilterRule cidrRule = TcpIpFilterHandler.parseRule("10.0.0.0/16", IpFilterRuleType.REJECT);
        assertThat(cidrRule.matches(new InetSocketAddress("10.0.1.5", 8080))).isTrue();
        assertThat(cidrRule.matches(new InetSocketAddress("10.1.0.1", 8080))).isFalse();

        // 单一 IPv6
        IpSubnetFilterRule v6Rule = TcpIpFilterHandler.parseRule("::1", IpFilterRuleType.ACCEPT);
        assertThat(v6Rule.matches(new InetSocketAddress("::1", 8080))).isTrue();

        // 空串防卫
        assertThatThrownBy(() -> TcpIpFilterHandler.parseRule("", IpFilterRuleType.ACCEPT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("IP 过滤规则表达式不能为空");
    }
}
