package io.github.atengk.boot.pay.config.properties;

import lombok.Data;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

import java.io.Serializable;
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
}
