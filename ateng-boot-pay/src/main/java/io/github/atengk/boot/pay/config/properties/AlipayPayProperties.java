package io.github.atengk.boot.pay.config.properties;

import lombok.Data;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;

/**
 * 支付宝全局与多应用配置属性
 *
 * @author Ateng
 * @since 2026-10-23
 */
@Data
public class AlipayPayProperties implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 是否启用支付宝模块
     */
    private boolean enabled = true;

    /**
     * 全局默认支付宝应用 ID 别名，默认值为 default
     */
    private String defaultApp = "default";

    /**
     * 是否开启服务商（ISV）模式
     */
    private boolean serviceProviderMode = false;

    /**
     * 命名多应用映射配置 (Key 为应用别名 ID，如 default、app1 等)
     */
    @NestedConfigurationProperty
    private Map<String, AlipayAppProperties> apps = new HashMap<>();
}
