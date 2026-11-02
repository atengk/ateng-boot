package io.github.atengk.boot.tcp.server;

import io.github.atengk.boot.tcp.event.TcpIpBlockedEvent;
import io.github.atengk.boot.tcp.properties.TcpIpFilterProperties;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.ipfilter.IpFilterRule;
import io.netty.handler.ipfilter.IpFilterRuleType;
import io.netty.handler.ipfilter.IpSubnetFilterRule;
import io.netty.handler.ipfilter.RuleBasedIpFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.util.StringUtils;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * TCP 网络层 IP 访问控制防火墙处理器
 * <p>
 * 基于 Netty {@link RuleBasedIpFilter} 实现，在 ChannelPipeline 索引第 0 位（早于 TLS 握手与编解码器）
 * 对入站远程连接实施纳秒级 IP 访问控制防卫：
 * <ul>
 *   <li><b>白名单严控模式</b>：当配置了白名单（支持单一 IP 及 CIDR）时，仅放行命中白名单的远程连接，非白名单连接在握手首位直接切断并发布 {@link TcpIpBlockedEvent}；</li>
 *   <li><b>黑名单拦截模式</b>：当白名单为空时，默认放行所有连接，但坚决阻断命中黑名单的恶意连接并发布 {@link TcpIpBlockedEvent}。</li>
 * </ul>
 * </p>
 *
 * @author Ateng
 * @since 2026-11-02
 */
@ChannelHandler.Sharable
public class TcpIpFilterHandler extends RuleBasedIpFilter {

    private static final Logger log = LoggerFactory.getLogger(TcpIpFilterHandler.class);

    private final TcpIpFilterProperties properties;
    private final ApplicationEventPublisher eventPublisher;
    private final List<RuleDefinition> configuredRules;
    private final boolean strictWhitelistMode;

    public TcpIpFilterHandler(TcpIpFilterProperties properties, ApplicationEventPublisher eventPublisher) {
        super(isAcceptIfNotFound(properties), toNettyRules(buildRuleDefinitions(properties)));
        this.properties = properties;
        this.eventPublisher = eventPublisher;
        this.configuredRules = Collections.unmodifiableList(buildRuleDefinitions(properties));
        this.strictWhitelistMode = properties != null && properties.getWhitelist() != null && !properties.getWhitelist().isEmpty();
    }

    private static boolean isAcceptIfNotFound(TcpIpFilterProperties properties) {
        if (properties == null) {
            return true;
        }
        List<String> whitelist = properties.getWhitelist();
        // 若配置了白名单，未命中白名单者一律拒绝 (acceptIfNotFound = false)
        return whitelist == null || whitelist.isEmpty();
    }

    private static List<RuleDefinition> buildRuleDefinitions(TcpIpFilterProperties properties) {
        if (properties == null) {
            return Collections.emptyList();
        }
        List<RuleDefinition> list = new ArrayList<>();
        // 1. 黑名单规则优先（先匹配黑名单阻断）
        if (properties.getBlacklist() != null) {
            for (String expr : properties.getBlacklist()) {
                if (StringUtils.hasText(expr)) {
                    IpSubnetFilterRule rule = parseRule(expr.trim(), IpFilterRuleType.REJECT);
                    list.add(new RuleDefinition(expr.trim(), IpFilterRuleType.REJECT, rule));
                }
            }
        }
        // 2. 白名单规则
        if (properties.getWhitelist() != null) {
            for (String expr : properties.getWhitelist()) {
                if (StringUtils.hasText(expr)) {
                    IpSubnetFilterRule rule = parseRule(expr.trim(), IpFilterRuleType.ACCEPT);
                    list.add(new RuleDefinition(expr.trim(), IpFilterRuleType.ACCEPT, rule));
                }
            }
        }
        return list;
    }

    private static IpFilterRule[] toNettyRules(List<RuleDefinition> definitions) {
        IpFilterRule[] rules = new IpFilterRule[definitions.size()];
        for (int i = 0; i < definitions.size(); i++) {
            rules[i] = definitions.get(i).rule();
        }
        return rules;
    }

    /**
     * 将 IP 字符串或 CIDR 表达式解析为 Netty {@link IpSubnetFilterRule}
     *
     * @param expression IP 地址或 CIDR 表达式（如 192.168.1.100 或 192.168.1.0/24）
     * @param ruleType 规则类型（ACCEPT 或 REJECT）
     * @return Netty IP 子网过滤规则
     */
    public static IpSubnetFilterRule parseRule(String expression, IpFilterRuleType ruleType) {
        if (!StringUtils.hasText(expression)) {
            throw new IllegalArgumentException("IP 过滤规则表达式不能为空");
        }
        String trimmed = expression.trim();
        if (trimmed.contains("/")) {
            return new IpSubnetFilterRule(trimmed, ruleType);
        }
        int cidrPrefix = trimmed.contains(":") ? 128 : 32;
        return new IpSubnetFilterRule(trimmed, cidrPrefix, ruleType);
    }

    @Override
    protected ChannelFuture channelRejected(ChannelHandlerContext ctx, InetSocketAddress remoteAddress) {
        String remoteIp = resolveRemoteIp(remoteAddress);
        String ruleDetail = resolveRejectReason(remoteAddress);

        log.warn("TCP 网络层 IP 防火墙拦截并拒绝未授权远程连接: remoteAddress={}, remoteIp={}, reason={}",
                remoteAddress, remoteIp, ruleDetail);

        if (eventPublisher != null) {
            try {
                eventPublisher.publishEvent(new TcpIpBlockedEvent(this, remoteIp, remoteAddress, ruleDetail));
            } catch (Exception ex) {
                log.error("发布 TcpIpBlockedEvent 领域事件异常: remoteIp={}", remoteIp, ex);
            }
        }

        return super.channelRejected(ctx, remoteAddress);
    }

    private String resolveRejectReason(InetSocketAddress remoteAddress) {
        for (RuleDefinition def : configuredRules) {
            if (def.rule().matches(remoteAddress)) {
                return "MATCH_BLACKLIST: " + def.expression();
            }
        }
        if (strictWhitelistMode) {
            return "NOT_IN_WHITELIST: 严格白名单模式已启用，客户端未在白名单放行列表内";
        }
        return "IP_BLOCKED: 默认策略阻断";
    }

    private String resolveRemoteIp(InetSocketAddress remoteAddress) {
        if (remoteAddress == null) {
            return "UNKNOWN";
        }
        if (remoteAddress.getAddress() != null) {
            return remoteAddress.getAddress().getHostAddress();
        }
        return remoteAddress.getHostString();
    }

    public List<RuleDefinition> getConfiguredRules() {
        return configuredRules;
    }

    public boolean isStrictWhitelistMode() {
        return strictWhitelistMode;
    }

    public TcpIpFilterProperties getProperties() {
        return properties;
    }

    /**
     * 规则封装记录
     *
     * @param expression 原始配置表达式
     * @param ruleType 规则类型
     * @param rule Netty 子网过滤规则实体
     */
    public record RuleDefinition(String expression, IpFilterRuleType ruleType, IpSubnetFilterRule rule) {
    }
}
