package io.github.atengk.boot.sample.sms.vo;

import io.github.atengk.boot.sms.model.SmsSupplier;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 短信渠道信息视图对象
 * 严格遵守零明文不变量，绝不包含 accessKeySecret 等敏感凭据
 *
 * @author Ateng
 * @since 2026-11-03
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SmsChannelVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 渠道名称
     */
    private String name;

    /**
     * 云服务商类型
     */
    private SmsSupplier supplier;

    /**
     * 默认短信签名
     */
    private String signName;

    /**
     * 应用标识（腾讯云 appId / 华为云 sender）
     */
    private String appId;

    /**
     * 物理地域标识
     */
    private String region;

    /**
     * 服务接入端点
     */
    private String endpoint;

    /**
     * 顺延容灾备用渠道名
     */
    private String failoverChannel;

    /**
     * 负载均衡权重
     */
    private int weight;

    /**
     * 模板逻辑标识与服务商物理模板ID映射关系
     */
    private java.util.Map<String, String> templates;
}
