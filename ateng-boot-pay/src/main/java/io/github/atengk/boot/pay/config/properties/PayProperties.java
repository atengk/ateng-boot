package io.github.atengk.boot.pay.config.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

import java.io.Serializable;

/**
 * 支付模块顶层根配置属性
 *
 * @author Ateng
 * @since 2026-10-23
 */
@Data
@ConfigurationProperties(prefix = "ateng.boot.pay")
public class PayProperties implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 支付模块全局总开关，默认为 true
     */
    private boolean enabled = true;

    /**
     * 异步 Webhook 回调与防重锁配置
     */
    @NestedConfigurationProperty
    private PayCallbackProperties callback = new PayCallbackProperties();

    /**
     * 单笔资金转账高敏配置（默认关闭）
     */
    @NestedConfigurationProperty
    private PayTransferProperties transfer = new PayTransferProperties();

    /**
     * 资金分账高敏配置（默认关闭）
     */
    @NestedConfigurationProperty
    private PayProfitSharingProperties profitSharing = new PayProfitSharingProperties();

    /**
     * 微信支付专属配置
     */
    @NestedConfigurationProperty
    private WechatPayProperties wechat = new WechatPayProperties();

    /**
     * 支付宝专属配置
     */
    @NestedConfigurationProperty
    private AlipayPayProperties alipay = new AlipayPayProperties();
}
