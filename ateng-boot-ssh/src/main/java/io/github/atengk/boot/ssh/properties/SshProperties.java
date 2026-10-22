package io.github.atengk.boot.ssh.properties;

import io.github.atengk.boot.ssh.enums.HostKeyPolicy;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * SSH 远程治理与通信基础设施全局配置属性
 *
 * @author Ateng
 * @since 2026-10-23
 */
@Data
@ConfigurationProperties(prefix = "ateng.boot.ssh")
public class SshProperties {

    /**
     * 是否启用 SSH 基础设施自动装配
     */
    private boolean enabled = true;

    /**
     * 默认目标服务器主机名或 IP 地址
     */
    private String host;

    /**
     * 默认目标服务器 SSH 端口
     */
    private int port = 22;

    /**
     * 默认连接用户名
     */
    private String username;

    /**
     * 登录口令密码
     */
    private String password;

    /**
     * 纯内存私钥内容（支持 OpenSSH / PKCS#8 / PEM 纯文本，严禁落地磁盘临时文件）
     */
    private String privateKey;

    /**
     * 本地磁盘私钥文件路径
     */
    private String privateKeyPath;

    /**
     * 加密私钥的密码短语
     */
    private String passphrase;

    /**
     * 主机公钥指纹校验策略（默认宽容自适应内存校验）
     */
    private HostKeyPolicy hostKeyPolicy = HostKeyPolicy.LENIENT;

    /**
     * 网络 TCP 连接与 SSH 协议协商超时阈值
     */
    private Duration connectTimeout = Duration.ofSeconds(10);

    /**
     * 用户凭据身份鉴权认证超时阈值
     */
    private Duration authTimeout = Duration.ofSeconds(15);

    /**
     * 远程命令执行超时阈值
     */
    private Duration commandTimeout = Duration.ofSeconds(60);

    /**
     * 会话连接池配置
     */
    @NestedConfigurationProperty
    private SshPoolProperties pool = new SshPoolProperties();

    /**
     * 命名多服务器节点字典（用于静态多主机映射）
     */
    private Map<String, SshProperties> servers = new LinkedHashMap<>();
}
