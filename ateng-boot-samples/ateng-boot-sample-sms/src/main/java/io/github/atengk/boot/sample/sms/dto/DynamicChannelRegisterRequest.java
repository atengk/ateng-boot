package io.github.atengk.boot.sample.sms.dto;

import io.github.atengk.boot.sms.model.SmsSupplier;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 动态注册短信渠道请求传输对象
 * 挂载 JSR-303 第一道防线参数校验
 *
 * @author Ateng
 * @since 2026-11-03
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DynamicChannelRegisterRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 渠道唯一标识名称
     */
    @NotBlank(message = "渠道名称不能为空")
    private String name;

    /**
     * 云服务商类型
     */
    @NotNull(message = "服务商类型不能为空")
    private SmsSupplier supplier;

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
     * 应用标识（腾讯云 SmsSdkAppId / 华为云通道号 from）
     */
    private String appId;

    /**
     * 物理地域标识
     */
    private String region;

    /**
     * 自定义服务接入端点 URL
     */
    private String endpoint;

    /**
     * 顺延容灾降级备用渠道名称
     */
    private String failoverChannel;
}
