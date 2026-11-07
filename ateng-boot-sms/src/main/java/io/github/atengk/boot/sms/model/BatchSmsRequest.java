package io.github.atengk.boot.sms.model;

import io.github.atengk.boot.core.util.BusinessAssert;
import lombok.Builder;
import lombok.Getter;

import java.io.Serializable;
import java.util.*;

/**
 * 批量/大规模营销短信发送领域请求对象
 * 封装手机号列表、模板编码、动态参数与渠道配置，由底座依据云厂商上限透明分批切片并发投递
 * 同时支持个性化批量发信（针对不同手机号配置差异化模板参数）
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
     * 统一模板动态参数模型（非个性化批量发信时生效）
     */
    private final SmsParam params;

    /**
     * 针对不同手机号的差异化个性化参数映射 (Phone -> SmsParam)
     */
    private final Map<String, SmsParam> phoneParams;

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

    public BatchSmsRequest(List<String> phones, String templateId, SmsParam params, Map<String, SmsParam> phoneParams, String signName, String channel, String bizId) {
        List<String> targetPhones = phones;
        if ((targetPhones == null || targetPhones.isEmpty()) && phoneParams != null && !phoneParams.isEmpty()) {
            targetPhones = new ArrayList<>(phoneParams.keySet());
        }
        BusinessAssert.notEmpty(targetPhones, "批量发信目标手机号列表不能为空");

        List<String> validPhones = new ArrayList<>(targetPhones.size());
        for (String p : targetPhones) {
            if (p != null && !p.isBlank()) {
                validPhones.add(p.trim());
            }
        }
        BusinessAssert.notEmpty(validPhones, "目标手机号列表中无有效号码");

        this.phones = Collections.unmodifiableList(validPhones);
        this.templateId = (templateId != null && !templateId.isBlank()) ? templateId.trim() : null;
        this.params = params != null ? params : SmsParam.create();
        this.phoneParams = phoneParams != null ? Collections.unmodifiableMap(new LinkedHashMap<>(phoneParams)) : Collections.emptyMap();
        this.signName = signName != null ? signName.trim() : null;
        this.channel = channel != null ? channel.trim() : null;
        this.bizId = bizId != null ? bizId.trim() : null;
    }

    public BatchSmsRequest(List<String> phones, String templateId, SmsParam params, String signName, String channel, String bizId) {
        this(phones, templateId, params, null, signName, channel, bizId);
    }

    /**
     * 判断当前请求是否属于个性化批量发信（手机号与参数一对一绑定）
     *
     * @return 是否为个性化批量发信
     */
    public boolean isPersonalized() {
        return phoneParams != null && !phoneParams.isEmpty();
    }

    /**
     * 获取个性化手机号参数映射（保底返回空 Map）
     *
     * @return 手机号 -> 差异化参数映射
     */
    public Map<String, SmsParam> getPhoneParams() {
        return phoneParams != null ? phoneParams : Collections.emptyMap();
    }

    /**
     * 基于当前批量发信请求复制并绑定目标物理模板、签名与执行渠道
     *
     * @param targetTemplateId 转译后的目标物理模板 ID
     * @param targetSignName   决策后的目标签名
     * @param targetChannel    实际执行发信的目标渠道名称
     * @return 新的 BatchSmsRequest 实例
     */
    public BatchSmsRequest withTemplateSignAndChannel(String targetTemplateId, String targetSignName, String targetChannel) {
        return new BatchSmsRequest(this.phones, targetTemplateId, this.params, this.phoneParams, targetSignName, targetChannel, this.bizId);
    }

    /**
     * 基于当前批量发信请求复制并绑定目标物理模板与签名
     *
     * @param targetTemplateId 转译后的目标物理模板 ID
     * @param targetSignName   决策后的目标签名
     * @return 新的 BatchSmsRequest 实例
     */
    public BatchSmsRequest withTemplateAndSign(String targetTemplateId, String targetSignName) {
        return withTemplateSignAndChannel(targetTemplateId, targetSignName, this.channel);
    }

    /**
     * 便捷静态构建器（缺省模板编码，回退采用渠道 defaultTemplateId）
     *
     * @param phones 目标手机号列表
     * @param params 动态参数模型
     * @return BatchSmsRequest 实例
     */
    public static BatchSmsRequest create(List<String> phones, SmsParam params) {
        return new BatchSmsRequest(phones, null, params, null, null, null, null);
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
        BusinessAssert.hasText(templateId, "短信模板编码不能为空");
        return new BatchSmsRequest(phones, templateId, params, null, null, null, null);
    }

    /**
     * 构建个性化批量发信请求（各手机号携带差异化参数）
     *
     * @param phoneParams 手机号与专属参数的映射表
     * @param templateId  短信模板编码
     * @return BatchSmsRequest 实例
     */
    public static BatchSmsRequest ofPersonalized(Map<String, SmsParam> phoneParams, String templateId) {
        BusinessAssert.notEmpty(phoneParams, "个性化批量发信参数映射不能为空");
        return ofPersonalized(phoneParams, templateId, null);
    }

    /**
     * 构建个性化批量发信请求（各手机号携带差异化参数，附带覆盖签名）
     *
     * @param phoneParams 手机号与专属参数的映射表
     * @param templateId  短信模板编码
     * @param signName    短信签名
     * @return BatchSmsRequest 实例
     */
    public static BatchSmsRequest ofPersonalized(Map<String, SmsParam> phoneParams, String templateId, String signName) {
        BusinessAssert.notEmpty(phoneParams, "个性化批量发信参数映射不能为空");
        return new BatchSmsRequest(new ArrayList<>(phoneParams.keySet()), templateId, null, phoneParams, signName, null, null);
    }
}
