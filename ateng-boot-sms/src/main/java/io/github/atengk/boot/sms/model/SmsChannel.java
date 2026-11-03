package io.github.atengk.boot.sms.model;

import lombok.Builder;
import lombok.Getter;
import lombok.ToString;

import java.io.Serializable;
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
     * 模板参数顺序配置（模板ID -> 有序参数名列表）
     */
    private final Map<String, List<String>> templateParamOrders;

    /**
     * 获取指定模板的参数映射顺序
     *
     * @param templateId 模板编码
     * @return 参数名顺序列表，无配置时返回空列表
     */
    public List<String> getParamOrder(String templateId) {
        if (templateParamOrders == null || templateId == null) {
            return Collections.emptyList();
        }
        List<String> order = templateParamOrders.get(templateId);
        return order != null ? order : Collections.emptyList();
    }
}
