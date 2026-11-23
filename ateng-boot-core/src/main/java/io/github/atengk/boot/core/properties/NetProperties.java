package io.github.atengk.boot.core.properties;

import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

/**
 * 网络通信与网卡探测配置属性
 * 提供首选 IP、首选网段及自定义排除网卡等强类型配置
 *
 * @author Ateng
 * @since 2026-11-04
 */
@Getter
@Setter
public class NetProperties {

    /**
     * 显式指定固定的局域网 IPv4 地址（逃生舱模式，非空时最高优先级直接采用）
     */
    private String preferredIp;

    /**
     * 首选网段列表（支持通配符如 192.168.3.* 或 CIDR 格式如 192.168.3.0/24）
     */
    private List<String> preferredNetworks = new ArrayList<>();

    /**
     * 自定义排除网卡名称列表（支持通配符如 VirtualBox.* 或正则，同时匹配网卡逻辑名与显示名）
     */
    private List<String> ignoredInterfaces = new ArrayList<>();
}
