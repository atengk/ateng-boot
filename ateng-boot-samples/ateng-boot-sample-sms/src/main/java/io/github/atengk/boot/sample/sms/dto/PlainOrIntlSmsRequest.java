package io.github.atengk.boot.sample.sms.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * 纯文本直发与国际区号短信请求传输对象
 *
 * @author Ateng
 * @since 2026-11-21
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PlainOrIntlSmsRequest {

    /**
     * 目标手机号（支持国际 E.164 如 +85291234567、+14155552671 或国内号码）
     */
    @NotBlank(message = "目标手机号不能为空")
    private String phone;

    /**
     * 短信模板编码（若传正文纯文本可留空）
     */
    private String templateId;

    /**
     * 纯文本直发正文
     */
    private String content;

    /**
     * 模板变量映射
     */
    private Map<String, String> params;

    /**
     * 覆盖短信签名
     */
    private String signName;

    /**
     * 指定渠道名称
     */
    private String channel;

    /**
     * 外部业务流水号
     */
    private String bizId;

    /**
     * 是否为紧急短信
     */
    @Builder.Default
    private boolean urgent = false;
}
