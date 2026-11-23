package io.github.atengk.boot.sms.properties;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;
import lombok.ToString;

import java.io.Serializable;

/**
 * 短信渠道 HTTP 出站代理配置属性
 * 用于内网隔离区应用通过指定正向 HTTP 代理（含可选 Basic 认证）访问公网短信网关
 *
 * @author Ateng
 * @since 2026-11-21
 */
@Data
@ToString(exclude = {"password"})
public class SmsProxyProperties implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 是否启用渠道级 HTTP 代理，默认 false
     */
    private boolean enabled = false;

    /**
     * HTTP 代理服务器主机名或 IP 地址
     */
    private String host;

    /**
     * HTTP 代理服务器端口号
     */
    private int port;

    /**
     * 代理认证用户名（可选）
     */
    private String username;

    /**
     * 代理认证密码（可选，脱敏排除序列化与日志打印）
     */
    @JsonIgnore
    private String password;

    /**
     * 创建防御性拷贝副本，防止外部配置变更影响已冻结渠道实例
     *
     * @return 拷贝后的代理配置
     */
    public SmsProxyProperties copy() {
        SmsProxyProperties copied = new SmsProxyProperties();
        copied.setEnabled(this.enabled);
        copied.setHost(this.host);
        copied.setPort(this.port);
        copied.setUsername(this.username);
        copied.setPassword(this.password);
        return copied;
    }
}
