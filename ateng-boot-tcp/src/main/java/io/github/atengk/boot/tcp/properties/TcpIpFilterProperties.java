package io.github.atengk.boot.tcp.properties;

import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

/**
 * TCP 网络层 IP 访问控制防火墙配置属性
 *
 * @author Ateng
 * @since 2026-11-02
 */
@Getter
@Setter
public class TcpIpFilterProperties {

    /**
     * 是否启用 IP 访问控制防火墙（默认 false）
     */
    private boolean enabled = false;

    /**
     * IP 白名单列表（支持单一 IP 如 192.168.1.100 或 CIDR 子网如 192.168.1.0/24）
     * <p>
     * <b>白名单严控模式</b>：若配置了白名单规则，则仅允许白名单内的客户端建立连接，其余所有连接一律在握手阶段强行切断。
     * </p>
     */
    private List<String> whitelist = new ArrayList<>();

    /**
     * IP 黑名单列表（支持单一 IP 如 10.0.0.99 或 CIDR 子网如 10.0.0.0/8）
     * <p>
     * 当且仅当白名单为空时，黑名单规则生效；命中黑名单的客户端在握手阶段被强行切断。
     * </p>
     */
    private List<String> blacklist = new ArrayList<>();
}
