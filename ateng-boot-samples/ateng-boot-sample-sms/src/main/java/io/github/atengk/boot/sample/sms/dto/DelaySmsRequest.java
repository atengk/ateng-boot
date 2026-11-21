package io.github.atengk.boot.sample.sms.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Map;

/**
 * 延迟或定时短信调度请求传输对象
 *
 * @author Ateng
 * @since 2026-11-21
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DelaySmsRequest {

    /**
     * 目标接收手机号（支持国内 11 位或 E.164 国际格式）
     */
    @NotBlank(message = "目标手机号不能为空")
    private String phone;

    /**
     * 模板编码（模板模式时传入；纯文本直发时留空）
     */
    private String templateId;

    /**
     * 纯文本短信正文内容（仅支持纯文本直发渠道生效）
     */
    private String content;

    /**
     * 模板动态参数
     */
    private Map<String, String> params;

    /**
     * 覆盖签名名称
     */
    private String signName;

    /**
     * 指定发送渠道名
     */
    private String channel;

    /**
     * 业务幂等追踪流水号
     */
    private String bizId;

    /**
     * 是否为紧急短信
     */
    @Builder.Default
    private boolean urgent = false;

    /**
     * 延迟秒数（与 triggerAt 二选一，未传 triggerAt 时生效）
     */
    @Min(value = 0, message = "延迟秒数不能为负数")
    @Builder.Default
    private long delaySeconds = 5;

    /**
     * 指定绝对定时触发时刻（传入时优先按定时时刻调度）
     */
    private Instant triggerAt;
}
