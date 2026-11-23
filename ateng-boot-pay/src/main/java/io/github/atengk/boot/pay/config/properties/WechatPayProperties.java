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
     * 新版商家转账默认转账场景 ID（可选，统一转账门面未显式传入时兜底使用，默认 1000）
     */
    private String defaultTransferSceneId = "1000";

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
     * HTTP 客户端网络超时配置
     */
    @NestedConfigurationProperty
    private PayHttpProperties http = new PayHttpProperties();

    /**
     * 微信线下条码付撤销冲正重试配置
     */
    @NestedConfigurationProperty
    private ReverseRetryProperties reverseRetry = new ReverseRetryProperties();

    /**
     * 微信条码付撤销安全兜底配置
     */
    @NestedConfigurationProperty
    private ReverseFallbackProperties reverseFallback = new ReverseFallbackProperties();

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
         * 重试初始退避间隔时间 (默认 1 秒)
         */
        private Duration delay = Duration.ofSeconds(1);

        /**
         * 指数退避倍率 (默认 2.0)
         */
        private double multiplier = 2.0;

        /**
         * 最大退避间隔时间 (默认 5 秒)
         */
        private Duration maxDelay = Duration.ofSeconds(5);
    }

    /**
     * 微信条码付撤销安全兜底策略配置属性
     */
    @Data
    public static class ReverseFallbackProperties implements Serializable {

        private static final long serialVersionUID = 1L;

        /**
         * 是否启用安全兜底（当网关反馈不支持直接撤销端点时，按先查单再关单/退款策略闭环处置，默认 false）
         */
        private boolean enabled = false;

        /**
         * 安全兜底查单确认订单已支付（SUCCESS）时的处置策略（默认 REJECT 拒绝自动退款）
         */
        private PaidPolicy paidPolicy = PaidPolicy.REJECT;

        /**
         * 安全兜底遇到已支付订单时的处置策略
         */
        public enum PaidPolicy {
            /**
             * 拒绝自动处置，抛出业务异常交由上层或人工核实
             */
            REJECT,
            /**
             * 自动发起原金额全额原路退款
             */
            AUTO_REFUND
        }
    }
}
