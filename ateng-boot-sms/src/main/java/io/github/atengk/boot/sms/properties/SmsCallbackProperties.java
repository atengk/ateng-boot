package io.github.atengk.boot.sms.properties;

import lombok.Data;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * 短信入站回调网关配置属性
 *
 * @author Ateng
 * @since 2026-11-02
 */
@Data
public class SmsCallbackProperties implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 是否开启运营商异步送达回执与上行短信回调网关控制器，默认 false
     */
    private boolean enabled = false;

    /**
     * 回调网关 URL 路由前缀，默认 /ateng-sms/callback
     */
    private String pathPrefix = "/ateng-sms/callback";

    /**
     * 回调网关安全校验令牌 (Token/Secret)；若配置，请求头须携带 X-Callback-Token
     */
    private String token;

    /**
     * 是否允许通过 URL Query 参数明文传递令牌 (?token=...)，默认 false（推荐仅使用请求头防止日志泄密）
     */
    private boolean allowQueryToken = false;

    /**
     * 回调来源 IP 白名单 CIDR 规则列表（如 10.0.0.0/8、192.168.1.0/24）；为空时不限制来源 IP
     */
    private List<String> allowedCidrs = new ArrayList<>();

    /**
     * 是否强制要求 Webhook 签名校验通过，默认 false
     */
    private boolean signatureRequired = false;
}
