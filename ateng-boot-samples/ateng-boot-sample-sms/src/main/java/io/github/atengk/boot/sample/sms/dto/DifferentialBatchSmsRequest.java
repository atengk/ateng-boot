package io.github.atengk.boot.sample.sms.dto;

import jakarta.validation.constraints.NotEmpty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.Map;

/**
 * 千人千面差异化个性化批量发信请求传输对象
 *
 * @author Ateng
 * @since 2026-11-07
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DifferentialBatchSmsRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 针对不同手机号的差异化个性化模板参数映射 (Phone -> Map<String, String>)
     */
    @NotEmpty(message = "差异化个性化参数映射不能为空")
    private Map<String, Map<String, String>> phoneParams;

    /**
     * 云厂商短信模板编码/ID
     */
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
     * 业务追踪流水号
     */
    private String bizId;

    /**
     * 是否为紧急优先批量发信（自动豁免夜间免打扰）
     */
    @Builder.Default
    private boolean urgent = false;
}
