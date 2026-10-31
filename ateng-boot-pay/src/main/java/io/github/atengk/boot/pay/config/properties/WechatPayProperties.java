package io.github.atengk.boot.pay.config.properties;

import lombok.Data;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

import java.io.Serializable;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * 微信支付全局与多商户配置属性
 *
 * @author Ateng
 * @since 2026-10-23
 */
@Data
public class WechatPayProperties implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 是否启用微信支付模块
     */
    private boolean enabled = true;

    /**
     * 全局默认商户 ID 别名，默认值为 default
     */
    private String defaultMerchant = "default";

    /**
     * 是否开启服务商（ISV）模式
     */
    private boolean serviceProviderMode = false;

    /**
     * 命名多商户映射配置 (Key 为商户别名 ID，如 default、store1 等)
     */
    @NestedConfigurationProperty
    private Map<String, WechatMerchantProperties> merchants = new HashMap<>();

    /**
     * 微信线下条码付撤销冲正重试配置
     */
    @NestedConfigurationProperty
    private ReverseRetryProperties reverseRetry = new ReverseRetryProperties();

    /**
     * 微信撤销冲正受控退避重试配置属性
     */
    @Data
    public static class ReverseRetryProperties implements Serializable {

        private static final long serialVersionUID = 1L;

        /**
         * 是否启用撤销冲正失败重试 (默认 true)
         */
        private boolean enabled = true;

        /**
         * 最大重试调用次数 (包括首次尝试，默认 3 次)
         */
        private int maxAttempts = 3;

        /**
         * 重试初始退避间隔时间 (默认 3 秒)
         */
        private Duration delay = Duration.ofSeconds(3);

        /**
         * 指数退避倍率 (默认 1.0)
         */
        private double multiplier = 1.0;

        /**
         * 最大退避间隔时间 (默认 3 秒)
         */
        private Duration maxDelay = Duration.ofSeconds(3);
    }
}
