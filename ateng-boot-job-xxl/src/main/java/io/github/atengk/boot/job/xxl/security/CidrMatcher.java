package io.github.atengk.boot.job.xxl.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 无臃肿 CIDR 掩码与 IP 白名单匹配工具
 *
 * <p>基于 JDK 原生网络原语与无符号位运算构建，支持单 IP 与标准 CIDR 网段（如 {@code 10.0.0.0/8}、{@code 192.168.1.0/24}），
 * 兼具高吞吐匹配性能与 IPv6 回环（{@code ::1}）兼容，用于 Netty 入站安全防火墙的来源 IP 防卫。</p>
 *
 * @author Ateng
 * @since 2026-10-31
 */
public class CidrMatcher {

    private static final Logger log = LoggerFactory.getLogger(CidrMatcher.class);

    private final List<CidrRule> rules;
    private final boolean allowAll;

    /**
     * 构造 CIDR 匹配器
     *
     * @param cidrList CIDR 或单 IP 规则列表；若为 null 或空集合，则默认放行所有来源
     */
    public CidrMatcher(List<String> cidrList) {
        if (cidrList == null || cidrList.isEmpty()) {
            this.rules = Collections.emptyList();
            this.allowAll = true;
            return;
        }

        List<CidrRule> parsedRules = new ArrayList<>();
        for (String item : cidrList) {
            if (!StringUtils.hasText(item)) {
                continue;
            }
            try {
                parsedRules.add(parseRule(item.trim()));
            } catch (Exception e) {
                log.error("解析 XXL-Job CIDR 白名单规则失败，该规则已被忽略: [{}]", item, e);
            }
        }

        this.rules = Collections.unmodifiableList(parsedRules);
        this.allowAll = this.rules.isEmpty();
    }

    /**
     * 判断指定客户端 IP 是否在允许的白名单范围内
     *
     * @param clientIp 客户端远程 IP 字符串
     * @return 若命中白名单或白名单为空放行全部则返回 true，否则返回 false
     */
    public boolean isAllowed(String clientIp) {
        if (allowAll) {
            return true;
        }
        if (!StringUtils.hasText(clientIp)) {
            return false;
        }

        String normalizedIp = normalizeIp(clientIp.trim());
        try {
            InetAddress address = InetAddress.getByName(normalizedIp);
            for (CidrRule rule : rules) {
                if (rule.matches(address)) {
                    return true;
                }
            }
        } catch (UnknownHostException e) {
            log.debug("非法客户端 IP 地址格式，防火墙直接拒绝: [{}]", clientIp);
            return false;
        }

        return false;
    }

    /**
     * 标准化 IP 地址（将各类 IPv6 回环形式统一转换为 127.0.0.1 简化匹配）
     */
    private static String normalizeIp(String ip) {
        if ("::1".equals(ip) || "0:0:0:0:0:0:0:1".equals(ip)) {
            return "127.0.0.1";
        }
        return ip;
    }

    private static CidrRule parseRule(String cidrStr) throws UnknownHostException {
        int slashIdx = cidrStr.indexOf('/');
        if (slashIdx < 0) {
            // 单 IP 规则
            String targetIp = normalizeIp(cidrStr);
            InetAddress target = InetAddress.getByName(targetIp);
            if (target instanceof Inet4Address) {
                return new Ipv4CidrRule(ipv4ToInt((Inet4Address) target), 32);
            } else {
                return new ExactAddressRule(target);
            }
        }

        String ipPart = normalizeIp(cidrStr.substring(0, slashIdx).trim());
        int prefix = Integer.parseInt(cidrStr.substring(slashIdx + 1).trim());

        InetAddress target = InetAddress.getByName(ipPart);
        if (target instanceof Inet4Address) {
            if (prefix < 0 || prefix > 32) {
                throw new IllegalArgumentException("IPv4 CIDR prefix 必须在 0 到 32 之间: " + cidrStr);
            }
            return new Ipv4CidrRule(ipv4ToInt((Inet4Address) target), prefix);
        } else {
            return new ExactAddressRule(target);
        }
    }

    private static int ipv4ToInt(Inet4Address address) {
        byte[] bytes = address.getAddress();
        return ((bytes[0] & 0xFF) << 24)
                | ((bytes[1] & 0xFF) << 16)
                | ((bytes[2] & 0xFF) << 8)
                | (bytes[3] & 0xFF);
    }

    /**
     * CIDR 规则匹配抽象
     */
    private interface CidrRule {
        boolean matches(InetAddress address);
    }

    /**
     * 高性能 IPv4 无符号位运算规则
     */
    private static class Ipv4CidrRule implements CidrRule {
        private final int targetSubnet;
        private final int mask;

        public Ipv4CidrRule(int targetIp, int prefix) {
            if (prefix == 0) {
                this.mask = 0;
            } else {
                this.mask = 0xFFFFFFFF << (32 - prefix);
            }
            this.targetSubnet = targetIp & this.mask;
        }

        @Override
        public boolean matches(InetAddress address) {
            if (address instanceof Inet4Address ipv4) {
                int clientInt = ipv4ToInt(ipv4);
                return (clientInt & this.mask) == this.targetSubnet;
            }
            return false;
        }
    }

    /**
     * 精确地址比对规则 (用于 IPv6 等)
     */
    private static class ExactAddressRule implements CidrRule {
        private final InetAddress expected;

        public ExactAddressRule(InetAddress expected) {
            this.expected = expected;
        }

        @Override
        public boolean matches(InetAddress address) {
            return expected.equals(address);
        }
    }

}
