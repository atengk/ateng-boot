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
     * 渠道默认短信模板 ID（发信请求未指定模板时的默认保底模板）
     */
    private String defaultTemplateId;

    /**
     * 厂商状态报告回调 URL
     */
    private String statusCallback;

    /**
     * 渠道权重（用于加权负载均衡调度），默认 1
     */
    private int weight = 1;

    /**
     * 单渠道网络/瞬态异常重试次数上限（默认 0 表示不重试）
     */
    private int maxRetries = 0;

    /**
     * 单渠道重试间隔，默认 1 秒
     */
    private Duration retryInterval = Duration.ofSeconds(1);

    /**
     * 渠道下绑定的特定模板配置映射（模板编码 -> 模板配置）
     */
    @NestedConfigurationProperty
    private Map<String, SmsTemplateProperties> templates = new LinkedHashMap<>();
}
