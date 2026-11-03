package io.github.atengk.boot.sms.properties;

import io.github.atengk.boot.sms.model.SmsSupplier;
import lombok.Data;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

import java.io.Serializable;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 单条短信渠道配置属性
 *
 * @author Ateng
 * @since 2026-11-02
 */
@Data
public class SmsChannelProperties implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 云厂商服务商类型，默认 MOCK 沙箱模式
     */
    private SmsSupplier supplier = SmsSupplier.MOCK;

    /**
     * 访问凭据 Key / SecretId / AppKey
     */
    private String accessKeyId;

    /**
     * 访问密钥 Secret / SecretKey / AppSecret
     */
    private String accessKeySecret;

    /**
     * 默认短信签名
     */
    private String signName;

    /**
     * 专属应用标识（腾讯云 SmsSdkAppId / 华为云通道号 from）
     */
    private String appId;

    /**
     * 物理地域标识（如 cn-hangzhou, ap-guangzhou, cn-north-4）
     */
    private String region;

    /**
     * 自定义服务接入端点 URL（为 null 时使用厂商官方默认公网端点）
     */
    private String endpoint;

    /**
     * 顺延容灾降级备用渠道名（为 null 表示不启用容灾备用通道）
     */
    private String failoverChannel;

    /**
     * HTTP 请求网络超时阈值，默认 10 秒
     */
    private Duration timeout = Duration.ofSeconds(10);

    /**
     * 渠道下绑定的特定模板配置映射（模板编码 -> 模板配置）
     */
    @NestedConfigurationProperty
    private Map<String, SmsTemplateProperties> templates = new LinkedHashMap<>();
}
