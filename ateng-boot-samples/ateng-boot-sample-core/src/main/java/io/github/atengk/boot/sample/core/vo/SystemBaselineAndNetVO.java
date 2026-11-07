package io.github.atengk.boot.sample.core.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 系统环境基线防卫与网络探测响应视图对象
 *
 * @author Ateng
 * @since 2026-11-07
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SystemBaselineAndNetVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 当前系统默认时区标识
     */
    private String timezone;

    /**
     * JVM 文件字符编码
     */
    private String fileEncoding;

    /**
     * 是否处于 Headless 无头模式
     */
    private Boolean headless;

    /**
     * JVM 底层网络默认连接超时（毫秒）
     */
    private String connectTimeout;

    /**
     * JVM 底层网络默认读取超时（毫秒）
     */
    private String readTimeout;

    /**
     * 宿主机最佳物理网卡内网 IPv4 地址
     */
    private String physicalIpv4;

    /**
     * 动态探测并分配的可用本地端口
     */
    private Integer usablePort;

    /**
     * 原始输入客户端 IP（含反向代理端口）
     */
    private String rawClientIp;

    /**
     * 剥离反向代理端口后的纯净客户端 IP
     */
    private String cleanedClientIp;

    /**
     * 客户端 IP 是否命中目标 CIDR 子网掩码规则
     */
    private Boolean cidrMatched;

    /**
     * 测试 IPv6 地址清洗剥离端口结果
     */
    private String ipv6Cleaned;

    /**
     * IPv6 地址是否匹配 IPv6 CIDR 网段规则
     */
    private Boolean ipv6CidrMatched;

    /**
     * Web 服务端口
     */
    private Integer serverPort;

    /**
     * Spring 容器当前激活的主 Profile
     */
    private String activeProfile;
}
