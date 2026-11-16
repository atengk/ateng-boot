package io.github.atengk.boot.mail.properties;

import io.github.atengk.boot.mail.model.AuthMode;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;
import org.springframework.validation.annotation.Validated;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 邮件发信账号配置属性
 *
 * @author Ateng
 * @since 2026-10-22
 */
@Data
@Validated
public class MailAccountProperties {

    /**
     * SMTP 服务器主机地址（如 smtp.163.com、smtp.qq.com）
     */
    private String host;

    /**
     * SMTP 服务端口（通常 SSL 端口为 465，非 SSL 端口为 25 或 587）
     */
    @Min(value = 1, message = "accounts.*.port 必须在 1 到 65535 之间")
    @Max(value = 65535, message = "accounts.*.port 必须在 1 到 65535 之间")
    private Integer port = 465;

    /**
     * 认证用户名（通常为发信邮箱地址）
     */
    private String username;

    /**
     * 认证密码或授权码（各大邮箱厂商均建议使用独立授权码）
     */
    private String password;

    /**
     * SMTP 认证模式，默认 AUTO（有凭据使用密码认证，无凭据自动免认证中继）
     */
    private AuthMode authMode = AuthMode.AUTO;

    /**
     * XOAUTH2 访问令牌（静态配置兜底，动态刷新建议实现 MailAccessTokenProvider SPI）
     */
    private String accessToken;

    /**
     * SSL 信任主机名配置（如内网主机名或 "*"，用于自签名网关覆写默认主机名一致性校验）
     */
    private String sslTrust;

    /**
     * 传输协议，默认为 smtp
     */
    private String protocol = "smtp";

    /**
     * 是否开启 SSL/TLS 安全加密传输，生产默认 true
     */
    private boolean ssl = true;

    /**
     * 是否开启 STARTTLS 加密传输，默认 false
     */
    private boolean starttls = false;

    /**
     * 发件人邮箱地址（别名字段，对齐 accounts.*.from 与 defaultFrom）
     */
    private String from;

    /**
     * 发件人中文昵称（别名字段，对齐 accounts.*.from-name 与 defaultFromName）
     */
    private String fromName;

    /**
     * 默认发件人邮箱地址（若未配置则回退到 username）
     */
    private String defaultFrom;

    /**
     * 默认发件人中文昵称（自动依据 RFC 2047 规范安全编码）
     */
    private String defaultFromName;

    /**
     * 当前账号专职合规审计归档密送邮箱（若未配置，则回退继承全局 archive-bcc）
     */
    private String archiveBcc;

    /**
     * 额外的底层 JavaMail 专有属性键值对
     */
    private Map<String, String> properties = new LinkedHashMap<>();

    public String getFrom() {
        return from != null && !from.isBlank() ? from : defaultFrom;
    }

    public void setFrom(String from) {
        this.from = from;
        this.defaultFrom = from;
    }

    public String getDefaultFrom() {
        return defaultFrom != null && !defaultFrom.isBlank() ? defaultFrom : from;
    }

    public void setDefaultFrom(String defaultFrom) {
        this.defaultFrom = defaultFrom;
        this.from = defaultFrom;
    }

    public String getFromName() {
        return fromName != null && !fromName.isBlank() ? fromName : defaultFromName;
    }

    public void setFromName(String fromName) {
        this.fromName = fromName;
        this.defaultFromName = fromName;
    }

    public String getDefaultFromName() {
        return defaultFromName != null && !defaultFromName.isBlank() ? defaultFromName : fromName;
    }

    public void setDefaultFromName(String defaultFromName) {
        this.defaultFromName = defaultFromName;
        this.fromName = defaultFromName;
    }
}
