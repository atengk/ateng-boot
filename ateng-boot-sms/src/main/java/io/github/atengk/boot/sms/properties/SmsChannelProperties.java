package io.github.atengk.boot.sms.properties;

import io.github.atengk.boot.sms.model.SmsQuotaResetCycle;
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
     * 自定义短信驱动编码（当注册多个自定义 SmsProvider 或需要显式覆盖 supplier 路由键时指定）
     */
    private String providerCode;

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
     * 云平台短信签名 ID（部分厂商接口要求使用签名编码而非签名文本时配置）
     */
    private String signId;

    /**
     * 短信通道扩展码 / 子号
     */
    private String extCode;

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
     * 是否开启国际及港澳台短信区号归一化适配，默认 false
     */
    private boolean internationalEnabled = false;

    /**
     * 开启国际短信适配时未带区号号码的默认国家码，默认 "+86"
     */
    private String defaultRegionCode = "+86";

    /**
     * 单渠道发信配额上限（0 表示不设上限），默认 0
     */
    private long maxQuota = 0L;

    /**
     * 单渠道配额重置周期（DAILY 自然日清零 | TOTAL 总量包干），默认 DAILY
     */
    private SmsQuotaResetCycle quotaResetCycle = SmsQuotaResetCycle.DAILY;

    /**
     * 渠道级独立 HTTP 出站代理配置
     */
    @NestedConfigurationProperty
    private SmsProxyProperties proxy = new SmsProxyProperties();

    /**
     * 单厂商独有扩展属性字典（避免配置模型被各家私有字段污染）
     */
    private Map<String, String> extras = new LinkedHashMap<>();

    /**
     * 渠道下绑定的特定模板配置映射（模板编码 -> 模板配置）
     */
    @NestedConfigurationProperty
    private Map<String, SmsTemplateProperties> templates = new LinkedHashMap<>();
}
