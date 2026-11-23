package io.github.atengk.boot.sms.properties;

import lombok.Data;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * 短信模板特定配置属性
 *
 * @author Ateng
 * @since 2026-11-02
 */
@Data
public class SmsTemplateProperties implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 物理模板 ID（对应服务商控制台中的真实 TemplateCode / TemplateId）
     */
    private String templateId;

    /**
     * 模板专属短信签名（若未配置则回退采用渠道默认 signName）
     */
    private String signName;

    /**
     * 模板参数顺序映射列表（用于将 Map 参数按序投射为腾讯云/华为云所需数组）
     */
    private List<String> paramOrder = new ArrayList<>();
}
