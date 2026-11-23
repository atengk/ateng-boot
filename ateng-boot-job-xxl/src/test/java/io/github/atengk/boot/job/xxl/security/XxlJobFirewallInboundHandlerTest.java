package io.github.atengk.boot.job.xxl.security;

import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link XxlJobFirewallInboundHandler} 入站安全防火墙单元测试（复用 Core {@code CidrMatcher}）
 *
 * @author Ateng
 * @since 2026-11-21
 */
@DisplayName("XXL-Job 入站安全防火墙 CIDR 白名单单元测试")
class XxlJobFirewallInboundHandlerTest {

    private static EmbeddedChannel channelWithRemoteIp(String ip, XxlJobFirewallInboundHandler handler) {
        return new EmbeddedChannel(handler) {
            @Override
            protected SocketAddress remoteAddress0() {
                return new InetSocketAddress(ip, 19090);
            }
        };
    }

    private static DefaultFullHttpRequest beatRequest() {
        return new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/beat");
    }

    @Test
    @DisplayName("白名单为空或 null 时默认不启用网段拦截，放行合法请求")
    void testEmptyWhitelistAllowsAll() {
        EmbeddedChannel emptyChannel = channelWithRemoteIp("10.0.0.5",
                new XxlJobFirewallInboundHandler(Collections.emptyList(), null, () -> false));
        assertTrue(emptyChannel.writeInbound(beatRequest()));
        assertNotNull(emptyChannel.readInbound());
        assertNull(emptyChannel.readOutbound());
        emptyChannel.finishAndReleaseAll();

        EmbeddedChannel nullChannel = channelWithRemoteIp("::1",
                new XxlJobFirewallInboundHandler(null, null, () -> false));
        assertTrue(nullChannel.writeInbound(beatRequest()));
        assertNotNull(nullChannel.readInbound());
        assertNull(nullChannel.readOutbound());
        nullChannel.finishAndReleaseAll();
    }

    @Test
    @DisplayName("配置 IPv4/IPv6 CIDR 白名单时精准放行并拦截非授权网段")
    void testIpv4AndIpv6CidrWhitelist() {
        List<String> whitelist = List.of("192.168.1.0/24", "2001:db8:1::/48", "127.0.0.1");

        EmbeddedChannel allowedV4 = channelWithRemoteIp("192.168.1.100",
                new XxlJobFirewallInboundHandler(whitelist, null, () -> false));
        assertTrue(allowedV4.writeInbound(beatRequest()));
        assertNotNull(allowedV4.readInbound());
        allowedV4.finishAndReleaseAll();

        EmbeddedChannel allowedV6 = channelWithRemoteIp("2001:db8:1::42",
                new XxlJobFirewallInboundHandler(whitelist, null, () -> false));
        assertTrue(allowedV6.writeInbound(beatRequest()));
        assertNotNull(allowedV6.readInbound());
        allowedV6.finishAndReleaseAll();

        EmbeddedChannel allowedLoopbackV6 = channelWithRemoteIp("::1",
                new XxlJobFirewallInboundHandler(whitelist, null, () -> false));
        assertTrue(allowedLoopbackV6.writeInbound(beatRequest()));
        assertNotNull(allowedLoopbackV6.readInbound());
        allowedLoopbackV6.finishAndReleaseAll();

        EmbeddedChannel rejected = channelWithRemoteIp("172.16.0.10",
                new XxlJobFirewallInboundHandler(whitelist, null, () -> false));
        assertFalse(rejected.writeInbound(beatRequest()));
        FullHttpResponse response = rejected.readOutbound();
        assertNotNull(response);
        assertEquals(HttpResponseStatus.FORBIDDEN, response.status());
        response.release();
        rejected.finishAndReleaseAll();
    }

    @Test
    @DisplayName("配置了非空但全部无效的 CIDR 规则时按 Fail-Closed 拦截请求")
    void testInvalidNonEmptyWhitelistFailsClosed() {
        EmbeddedChannel ch = channelWithRemoteIp("127.0.0.1",
                new XxlJobFirewallInboundHandler(List.of("invalid-cidr-rule"), null, () -> false));
        assertFalse(ch.writeInbound(beatRequest()));
        FullHttpResponse response = ch.readOutbound();
        assertNotNull(response);
        assertEquals(HttpResponseStatus.FORBIDDEN, response.status());
        response.release();
        ch.finishAndReleaseAll();
    }
}
