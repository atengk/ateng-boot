package io.github.atengk.boot.mail.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 发信账号领域模型与运行时实体
 *
 * @author Ateng
 * @since 2026-10-22
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MailAccount {

    /**
     * 账号唯一标识名称（如 default, ops, marketing）
     */
    private String name;

    /**
     * SMTP 服务器主机
     */
    private String host;

    /**
     * SMTP 服务端口
     */
    private int port;

    /**
     * 认证用户名
     */
    private String username;

    /**
     * 认证密码或授权码
     */
    private String password;

    /**
     * SMTP 认证模式（默认 AUTO：有凭据自动认证，无凭据自动免认证中继）
     */
    @Builder.Default
    private AuthMode authMode = AuthMode.AUTO;

    /**
     * XOAUTH2 静态或初始访问令牌（可选，动态刷新优先通过 MailAccessTokenProvider SPI 获取）
     */
    private String accessToken;

    /**
     * SSL/TLS 信任主机名配置（如指定内网网关域名或 "*"，用于自签名或非公网 CA 环境覆写）
     */
    private String sslTrust;

    /**
     * 传输协议
     */
    @Builder.Default
    private String protocol = "smtp";

    /**
     * 是否开启 SSL 加密
     */
    @Builder.Default
    private boolean ssl = true;

    /**
     * 是否开启 STARTTLS
     */
    @Builder.Default
    private boolean starttls = false;

    /**
     * 默认发件人邮箱
     */
    private String defaultFrom;

    /**
     * 默认发件人中文昵称
     */
    private String defaultFromName;

    /**
     * 合规审计归档密送邮箱（支持逗号或分号分隔多个邮箱）
     */
    private String archiveBcc;

    /**
     * 额外的专有属性
     */
    @Builder.Default
    private Map<String, String> properties = new LinkedHashMap<>();

    /**
     * 主账号遇瞬态可用性故障且自身重试耗尽时的有序备用账号名称列表
     */
    @Builder.Default
    private List<String> backupAccounts = new ArrayList<>();

    /**
     * 底层池化物理发送器实例
     */
    private JavaMailSenderImpl sender;

    /**
     * 生成账号核心连接参数的唯一指纹（用于动态账号 Sender 的 LRU 缓存复用）
     *
     * @return 账号指纹字符串
     */
    public String toFingerprint() {
        return (host != null ? host : "") + ":"
                + port + ":"
                + (username != null ? username : "") + ":"
                + (password != null ? password.hashCode() : 0) + ":"
                + resolveEffectiveAuthMode().name() + ":"
                + (authMode != null ? authMode.name() : AuthMode.AUTO.name()) + ":"
                + (accessToken != null ? accessToken.hashCode() : 0) + ":"
                + (sslTrust != null ? sslTrust : "") + ":"
                + (protocol != null ? protocol : "smtp") + ":"
                + ssl + ":"
                + starttls + ":"
                + (properties != null ? properties.hashCode() : 0);
    }

    /**
     * 解析当前账号实际生效的认证模式（将 AUTO 依据凭据/令牌收敛为 PASSWORD / XOAUTH2 / NONE）
     *
     * @return 生效的认证模式
     */
    public AuthMode resolveEffectiveAuthMode() {
        if (authMode != null && authMode != AuthMode.AUTO) {
            return authMode;
        }
        boolean hasUserOrPwd = (username != null && !username.isBlank()) || (password != null && !password.isBlank());
        if (hasUserOrPwd) {
            return AuthMode.PASSWORD;
        }
        if (accessToken != null && !accessToken.isBlank()) {
            return AuthMode.XOAUTH2;
        }
        return AuthMode.NONE;
    }

    /**
     * 获取静态配置的 XOAUTH2 访问令牌（优先读取 accessToken，其次回退 password）
     *
     * @return 静态令牌或 null
     */
    public String resolveStaticAccessToken() {
        if (accessToken != null && !accessToken.isBlank()) {
            return accessToken;
        }
        return (password != null && !password.isBlank()) ? password : null;
    }

    /**
     * 基于当前账号模型快速衍生绑定指定物理 Sender 的新账号实例（消除外部 Feature Envy）
     *
     * @param sender 物理 JavaMailSenderImpl 发送器
     * @return 绑定了物理 Sender 的新账号实例
     */
    public MailAccount withSender(JavaMailSenderImpl sender) {
        return MailAccount.builder()
                .name(this.name != null ? this.name : "dynamic")
                .host(this.host)
                .port(this.port)
                .username(this.username)
                .password(this.password)
                .authMode(this.authMode != null ? this.authMode : AuthMode.AUTO)
                .accessToken(this.accessToken)
                .sslTrust(this.sslTrust)
                .protocol(this.protocol)
                .ssl(this.ssl)
                .starttls(this.starttls)
                .defaultFrom(this.defaultFrom)
                .defaultFromName(this.defaultFromName)
                .archiveBcc(this.archiveBcc)
                .properties(this.properties != null ? new LinkedHashMap<>(this.properties) : new LinkedHashMap<>())
                .backupAccounts(this.backupAccounts != null ? new ArrayList<>(this.backupAccounts) : new ArrayList<>())
                .sender(sender)
                .build();
    }

    /**
     * 推导当前账号的邮件发信域名（用于 RFC 5322 Message-ID 生成）
     *
     * @return 域名字符串
     */
    public String getDomain() {
        if (username != null && username.contains("@")) {
            return username.substring(username.indexOf('@') + 1);
        }
        if (host != null && !host.isBlank()) {
            return host;
        }
        return "ateng-boot.local";
    }
}
