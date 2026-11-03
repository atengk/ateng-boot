package io.github.atengk.boot.sms.properties;

import lombok.Data;

import java.io.Serializable;

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
}
