package io.github.atengk.boot.sample.sms.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * 批量短信发信请求传输对象
 *
 * @author Ateng
 * @since 2026-11-02
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BatchSmsSendRequest {

    /**
     * 目标接收手机号集合（支持 E.164 或标准 11 位国内手机号）
     */
    @NotEmpty(message = "接收手机号列表不能为空")
    private List<@NotBlank(message = "手机号不能为空") @Pattern(regexp = "^(\\+?86)?1[3-9]\\d{9}$", message = "手机号格式不正确") String> phones;

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
     * 目标发信渠道名称
     */
    private String channel;

    /**
     * 模板通用动态变量占位符键值对
     */
    private Map<String, String> params;
}
