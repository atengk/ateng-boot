package io.github.atengk.boot.sms.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.github.atengk.boot.sms.properties.SmsTemplateProperties;
import lombok.Builder;
import lombok.Getter;
import lombok.ToString;

import java.io.Serializable;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 短信渠道领域模型
 * 封装单条短信渠道的认证凭据、默认签名与模板参数配置
 *
 * @author Ateng
 * @since 2026-11-02
 */
@Getter
@Builder
@ToString(exclude = {"accessKeySecret"})
public class SmsChannel implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 渠道唯一标识名称
     */
    private final String name;

    /**
     * 云厂商类型
     */
    private final SmsSupplier supplier;

    /**
     * 访问凭据 Key / SecretId / AppKey
     */
    private final String accessKeyId;

    /**
     * 访问密钥 Secret / SecretKey / AppSecret
     */
    @JsonIgnore
    private final String accessKeySecret;

    /**
     * 默认短信签名
     */
    private final String signName;

    /**
     * 应用标识（腾讯云 SmsSdkAppId / 华为云通道号 from）
     */
    private final String appId;

    /**
     * 物理地域标识（如 cn-hangzhou, ap-guangzhou, cn-north-4）
     */
    private final String region;

    /**
     * 自定义服务接入端点 URL（可为 null，默认使用公网端点）
     */
    private final String endpoint;

    /**
     * 顺延容灾降级备用渠道名称（为 null 表示无备用渠道）
     */
    private final String failoverChannel;

    /**
     * 网络请求超时时间
     */
    private final Duration timeout;

    /**
     * 渠道默认短信模板 ID（发信请求未指定模板时的保底模板）
     */
    private final String defaultTemplateId;

    /**
     * 厂商状态报告回调 URL
     */
    private final String statusCallback;

    /**
     * 渠道权重（用于加权负载均衡调度），默认 1
     */
    @Builder.Default
    private final int weight = 1;

    /**
     * 单渠道网络/瞬态异常重试次数上限（默认 0 表示不重试）
     */
    @Builder.Default
    private final int maxRetries = 0;

    /**
     * 单渠道重试间隔，默认 1 秒
     */
    @Builder.Default
    private final Duration retryInterval = Duration.ofSeconds(1);

    /**
     * 渠道下绑定的特定模板配置映射（模板编码 -> 模板配置）
     */
    private final Map<String, SmsTemplateProperties> templates;

    /**
     * 模板参数顺序配置（模板ID -> 有序参数名列表）
     */
    private final Map<String, List<String>> templateParamOrders;

    /**
     * 跨云逻辑模板 ID 转译
     * 若配置了 templates 且命中逻辑模板，返回配置的物理 templateId；
     * 若未配置物理 templateId 但 logicalTemplateId 非空，原样返回 logicalTemplateId（向后兼容）；
     * 若 logicalTemplateId 为空，回退返回渠道 defaultTemplateId
     *
     * @param logicalTemplateId 逻辑模板标识 / 模板 ID
     * @return 物理模板 ID 或 defaultTemplateId，未找到时返回 null
     */
    public String resolveTemplateId(String logicalTemplateId) {
        if (logicalTemplateId != null && !logicalTemplateId.isBlank()) {
            String key = logicalTemplateId.trim();
            if (templates != null) {
                SmsTemplateProperties prop = templates.get(key);
                if (prop != null && prop.getTemplateId() != null && !prop.getTemplateId().isBlank()) {
                    return prop.getTemplateId().trim();
                }
            }
            return key;
        }
        return (defaultTemplateId != null && !defaultTemplateId.isBlank()) ? defaultTemplateId.trim() : null;
    }

    /**
     * 短信签名动态决策
     * 优先级：显式入参 > 逻辑模板专属签名 > 渠道默认签名
     *
     * @param logicalTemplateId 逻辑模板标识
     * @param explicitSignName  显式指定的签名（可为 null）
     * @return 最终决策的签名
     */
    public String resolveSignName(String logicalTemplateId, String explicitSignName) {
        if (explicitSignName != null && !explicitSignName.isBlank()) {
            return explicitSignName.trim();
        }
        if (logicalTemplateId != null && !logicalTemplateId.isBlank() && templates != null) {
            SmsTemplateProperties prop = templates.get(logicalTemplateId.trim());
            if (prop != null && prop.getSignName() != null && !prop.getSignName().isBlank()) {
                return prop.getSignName().trim();
            }
        }
        return this.signName;
    }

    /**
     * 获取指定模板的参数映射顺序
     *
     * @param templateId 模板编码或逻辑标识
     * @return 参数名顺序列表，无配置时返回空列表
     */
    public List<String> getParamOrder(String templateId) {
        if (templateId == null || templateId.isBlank()) {
            return Collections.emptyList();
        }
        String key = templateId.trim();
        if (templates != null) {
            SmsTemplateProperties prop = templates.get(key);
            if (prop != null && prop.getParamOrder() != null && !prop.getParamOrder().isEmpty()) {
                return Collections.unmodifiableList(prop.getParamOrder());
            }
            for (SmsTemplateProperties p : templates.values()) {
                if (p != null && key.equalsIgnoreCase(p.getTemplateId()) && p.getParamOrder() != null && !p.getParamOrder().isEmpty()) {
                    return Collections.unmodifiableList(p.getParamOrder());
                }
            }
        }
        if (templateParamOrders != null) {
            List<String> order = templateParamOrders.get(key);
            if (order != null && !order.isEmpty()) {
                return Collections.unmodifiableList(order);
            }
        }
        return Collections.emptyList();
    }
}
