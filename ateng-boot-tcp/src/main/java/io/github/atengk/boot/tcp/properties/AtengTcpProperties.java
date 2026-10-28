package io.github.atengk.boot.tcp.properties;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * ateng-boot-tcp 全局核心配置属性类
 *
 * @author Ateng
 * @since 2026-10-26
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "ateng.boot.tcp")
public class AtengTcpProperties {

    /**
     * 全局 TCP 模块总开关（默认 true）
     */
    private boolean enabled = true;

    /**
     * TCP 服务端主配置（默认监听 0.0.0.0:8088）
     */
    @NestedConfigurationProperty
    private TcpServerProperties server = new TcpServerProperties();

    /**
     * TCP 客户端主配置（默认连接 127.0.0.1:8088）
     */
    @NestedConfigurationProperty
    private TcpClientProperties client = new TcpClientProperties();

    /**
     * 多端口多协议服务端监听配置映射表（key 为自定义服务名称，如 jt808, modbus）
     */
    @NestedConfigurationProperty
    private Map<String, TcpServerProperties> servers = new LinkedHashMap<>();

    /**
     * 命名多客户端长连接配置映射表（key 为自定义客户端名称，如 partnerA, bankGateway）
     */
    @NestedConfigurationProperty
    private Map<String, TcpClientProperties> clients = new LinkedHashMap<>();

    /**
     * 集群跨节点协同配置
     */
    @NestedConfigurationProperty
    private TcpClusterProperties cluster = new TcpClusterProperties();
}
