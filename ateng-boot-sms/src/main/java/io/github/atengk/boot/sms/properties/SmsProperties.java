package io.github.atengk.boot.sms.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

import java.io.Serializable;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * ateng-boot 短信底座统一配置属性
 *
 * @author Ateng
 * @since 2026-11-02
 */
@Data
@ConfigurationProperties(prefix = "ateng.boot.sms")
public class SmsProperties implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 是否启用短信底座基础设施，默认 true
     */
    private boolean enabled = true;

    /**
     * 系统默认发信渠道名称，默认 "mock"
     */
    private String defaultChannel = "mock";

    /**
     * 多命名渠道配置映射（渠道名称 -> 渠道属性）
     */
    @NestedConfigurationProperty
    private Map<String, SmsChannelProperties> channels = new LinkedHashMap<>();

    /**
     * 验证码全生命周期治理配置
     */
    @NestedConfigurationProperty
    private SmsVerifyCodeProperties verifyCode = new SmsVerifyCodeProperties();

    /**
     * 滑动窗口频控防刷配置
     */
    @NestedConfigurationProperty
    private SmsRateLimitProperties rateLimit = new SmsRateLimitProperties();

    /**
     * 出站安全防御与成本熔断配置
     */
    @NestedConfigurationProperty
    private SmsGuardProperties guard = new SmsGuardProperties();

    /**
     * 运营商异步回执与上行短信回调网关配置
     */
    @NestedConfigurationProperty
    private SmsCallbackProperties callback = new SmsCallbackProperties();
}
