package io.github.atengk.boot.tcp.event;

import org.springframework.context.ApplicationEvent;

import java.net.InetSocketAddress;

/**
 * TCP 网络层 IP 访问控制防火墙拦截领域事件
 * <p>
 * 当非白名单 IP 或黑名单 IP 尝试建立 TCP 连接时，在 Netty Pipeline 握手最前置阶段直接阻断物理通道并向 Spring 容器发布此审计事件。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-02
 */
public class TcpIpBlockedEvent extends ApplicationEvent {

    private final String remoteIp;
    private final InetSocketAddress remoteAddress;
    private final String ruleDetail;

    public TcpIpBlockedEvent(Object source, String remoteIp, InetSocketAddress remoteAddress, String ruleDetail) {
        super(source);
        this.remoteIp = remoteIp;
        this.remoteAddress = remoteAddress;
        this.ruleDetail = ruleDetail;
    }

    /**
     * 获取被拦截的客户端远程 IP 地址字符串
     *
     * @return 客户端 IP 地址
     */
    public String getRemoteIp() {
        return remoteIp;
    }

    /**
     * 获取被拦截的客户端远程完整 Socket 地址
     *
     * @return 包含 IP 与端口的 Socket 地址
     */
    public InetSocketAddress getRemoteAddress() {
        return remoteAddress;
    }

    /**
     * 获取触发拦截的规则匹配详情或原因说明
     *
     * @return 规则详情（如 MATCH_BLACKLIST: 10.0.0.1 或 NOT_IN_WHITELIST 等）
     */
    public String getRuleDetail() {
        return ruleDetail;
    }
}
