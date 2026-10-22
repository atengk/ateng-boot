package io.github.atengk.boot.mail.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import java.util.LinkedHashMap;
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
     * 额外的专有属性
     */
    @Builder.Default
    private Map<String, String> properties = new LinkedHashMap<>();

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
                + (protocol != null ? protocol : "smtp") + ":"
                + ssl + ":"
                + starttls;
    }
}
