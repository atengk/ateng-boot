package io.github.atengk.boot.sample.sms.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * 单条短信发信请求传输对象
 *
 * @author Ateng
 * @since 2026-11-02
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SendSmsRequest {

    /**
     * 目标接收手机号 (E.164 或标准 11 位国内手机号)
     */
    @NotBlank(message = "手机号不能为空")
    @Pattern(regexp = "^(\\+?86)?1[3-9]\\d{9}$", message = "手机号格式不正确")
    private String phone;

    /**
     * 云厂商短信模板编码/ID
     */
    @NotBlank(message = "短信模板ID不能为空")
    private String templateId;

    /**
     * 自定义指定短信签名（若留空则自动选用渠道默认配置）
     */
    private String signName;

    /**
     * 目标发信渠道名称（若留空则采用默认渠道或基于规则智能路由）
     */
    private String channel;

    /**
     * 模板动态变量占位符键值对
     */
    private Map<String, String> params;
}
