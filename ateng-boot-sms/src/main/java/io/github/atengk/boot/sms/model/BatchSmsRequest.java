package io.github.atengk.boot.sms.model;

import io.github.atengk.boot.core.util.BusinessAssert;
import lombok.Builder;
import lombok.Getter;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 批量/大规模营销短信发送领域请求对象
 * 封装手机号列表、模板编码、动态参数与渠道配置，由底座依据云厂商上限透明分批切片并发投递
 *
 * @author Ateng
 * @since 2026-11-02
 */
@Getter
@Builder
public class BatchSmsRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 目标接收人手机号列表（真明文）
     */
    private final List<String> phones;

    /**
     * 短信模板编码 / ID
     */
    private final String templateId;

    /**
     * 模板动态参数模型
     */
    private final SmsParam params;

    /**
     * 覆盖短信签名（为 null 时使用渠道默认签名）
     */
    private final String signName;

    /**
     * 指定发信渠道名（为 null 时使用默认/上下文渠道）
     */
    private final String channel;

    /**
     * 业务追踪流水号
     */
    private final String bizId;

    public BatchSmsRequest(List<String> phones, String templateId, SmsParam params, String signName, String channel, String bizId) {
        BusinessAssert.notEmpty(phones, "批量发信目标手机号列表不能为空");
        BusinessAssert.hasText(templateId, "短信模板编码不能为空");

        List<String> validPhones = new ArrayList<>(phones.size());
        for (String p : phones) {
            if (p != null && !p.isBlank()) {
                validPhones.add(p.trim());
            }
        }
        BusinessAssert.notEmpty(validPhones, "目标手机号列表中无有效号码");

        this.phones = Collections.unmodifiableList(validPhones);
        this.templateId = templateId.trim();
        this.params = params != null ? params : SmsParam.create();
        this.signName = signName != null ? signName.trim() : null;
        this.channel = channel != null ? channel.trim() : null;
        this.bizId = bizId != null ? bizId.trim() : null;
    }

    /**
     * 便捷静态构建器
     *
     * @param phones     目标手机号列表
     * @param templateId 短信模板编码
     * @param params     动态参数模型
     * @return BatchSmsRequest 实例
     */
    public static BatchSmsRequest create(List<String> phones, String templateId, SmsParam params) {
        return new BatchSmsRequest(phones, templateId, params, null, null, null);
    }
}
