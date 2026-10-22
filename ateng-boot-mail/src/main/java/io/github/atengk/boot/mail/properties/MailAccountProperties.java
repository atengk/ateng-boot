package io.github.atengk.boot.mail.properties;

import lombok.Data;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 邮件发信账号配置属性
 *
 * @author Ateng
 * @since 2026-10-22
 */
@Data
public class MailAccountProperties {

    /**
     * SMTP 服务器主机地址（如 smtp.163.com、smtp.qq.com）
     */
    private String host;

    /**
     * SMTP 服务端口（通常 SSL 端口为 465，非 SSL 端口为 25 或 587）
     */
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
     * 默认发件人邮箱地址（若未配置则回退到 username）
     */
    private String defaultFrom;

    /**
     * 默认发件人中文昵称（自动依据 RFC 2047 规范安全编码）
     */
    private String defaultFromName;

    /**
     * 额外的底层 JavaMail 专有属性键值对
     */
    private Map<String, String> properties = new LinkedHashMap<>();
}
