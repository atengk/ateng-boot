package io.github.atengk.boot.pay.config.properties;

import lombok.Data;

import java.io.Serializable;
import java.time.Duration;

/**
 * 支付异步 Webhook 回调与防重配置属性
 *
 * @author Ateng
 * @since 2026-10-23
 */
@Data
public class PayCallbackProperties implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 自动装配的回调端点统一前缀，默认为 /ateng-pay/callback
     */
    private String pathPrefix = "/ateng-pay/callback";

    /**
     * 回调并发防重锁过期超时时间，默认为 10 秒
     */
    private Duration idempotentTimeout = Duration.ofSeconds(10);
}
