package io.github.atengk.boot.sms.model;

import io.github.atengk.boot.core.util.BusinessAssert;
import io.github.atengk.boot.core.util.DesensitizeUtil;
import lombok.Builder;
import lombok.Getter;

import java.io.Serializable;
import java.util.Collections;
import java.util.List;

/**
 * 单条或批量短信发送领域请求对象
 * 内存中保持真明文，日志打印与外部表示强制执行手机号敏感脱敏
 *
 * @author Ateng
 * @since 2026-11-02
 */
@Getter
@Builder
public class SmsRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 目标接收人单手机号
     */
    private final String phone;

    /**
     * 批量发信手机号列表（支持一次投递多个目标号码）
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

    public SmsRequest(String phone, List<String> phones, String templateId, SmsParam params, String signName, String channel, String bizId) {
        this.templateId = (templateId != null && !templateId.isBlank()) ? templateId.trim() : null;
        this.params = params != null ? params : SmsParam.create();
        this.signName = signName != null ? signName.trim() : null;
        this.channel = channel != null ? channel.trim() : null;
        this.bizId = bizId != null ? bizId.trim() : null;

        if (phones != null && !phones.isEmpty()) {
            this.phones = Collections.unmodifiableList(phones);
            this.phone = (phone != null && !phone.isBlank()) ? phone.trim() : phones.get(0);
        } else {
            BusinessAssert.hasText(phone, "目标手机号不能为空");
            this.phone = phone.trim();
            this.phones = List.of(this.phone);
        }
    }

    public SmsRequest(String phone, String templateId, SmsParam params, String signName, String channel, String bizId) {
        this(phone, null, templateId, params, signName, channel, bizId);
    }

    /**
     * 基于当前发信请求复制并绑定目标物理模板、签名与执行渠道
     *
     * @param targetTemplateId 转译后的目标物理模板 ID
     * @param targetSignName   决策后的目标签名
     * @param targetChannel    实际执行发信的目标渠道名称
     * @return 新的 SmsRequest 实例
     */
    public SmsRequest withTemplateSignAndChannel(String targetTemplateId, String targetSignName, String targetChannel) {
        return new SmsRequest(this.phone, this.phones, targetTemplateId, this.params, targetSignName, targetChannel, this.bizId);
    }

    /**
     * 基于当前发信请求复制并绑定目标物理模板与签名
     *
     * @param targetTemplateId 转译后的目标物理模板 ID
     * @param targetSignName   决策后的目标签名
     * @return 新的 SmsRequest 实例
     */
    public SmsRequest withTemplateAndSign(String targetTemplateId, String targetSignName) {
        return withTemplateSignAndChannel(targetTemplateId, targetSignName, this.channel);
    }

    /**
     * 基于当前发信请求复制并指定执行渠道
     *
     * @param targetChannel 目标发信渠道名称
     * @return 新的 SmsRequest 实例
     */
    public SmsRequest withChannel(String targetChannel) {
        return new SmsRequest(this.phone, this.phones, this.templateId, this.params, this.signName, targetChannel, this.bizId);
    }

    /**
     * 获取发信手机号码列表（保底返回非空列表）
     *
     * @return 手机号码列表
     */
    public List<String> getPhones() {
        if (phones != null && !phones.isEmpty()) {
            return phones;
        }
        if (phone != null && !phone.isBlank()) {
            return List.of(phone);
        }
        return Collections.emptyList();
    }

    /**
     * 便捷静态构建器（缺省模板编码，回退采用渠道 defaultTemplateId）
     *
     * @param phone 目标手机号
     * @return SmsRequestBuilder
     */
    public static SmsRequestBuilder newBuilder(String phone) {
        return builder().phone(phone);
    }

    /**
     * 便捷静态构建器
     *
     * @param phone      目标手机号
     * @param templateId 模板编码
     * @return SmsRequestBuilder
     */
    public static SmsRequestBuilder newBuilder(String phone, String templateId) {
        return builder().phone(phone).templateId(templateId);
    }

    /**
     * 便捷静态工厂：单号码与参数（缺省模板编码，回退采用渠道 defaultTemplateId）
     *
     * @param phone  目标手机号
     * @param params 模板参数
     * @return SmsRequest 实例
     */
    public static SmsRequest of(String phone, SmsParam params) {
        return builder().phone(phone).params(params).build();
    }

    /**
     * 便捷静态工厂：单号码与模板
     *
     * @param phone      目标手机号
     * @param templateId 模板编码
     * @return SmsRequest 实例
     */
    public static SmsRequest of(String phone, String templateId) {
        BusinessAssert.hasText(templateId, "短信模板编码不能为空");
        return builder().phone(phone).templateId(templateId).build();
    }

    /**
     * 便捷静态工厂：单号码、模板与参数
     *
     * @param phone      目标手机号
     * @param templateId 模板编码
     * @param params     模板参数
     * @return SmsRequest 实例
     */
    public static SmsRequest of(String phone, String templateId, SmsParam params) {
        BusinessAssert.hasText(templateId, "短信模板编码不能为空");
        return builder().phone(phone).templateId(templateId).params(params).build();
    }

    /**
     * 自定义 Builder 增强方法
     */
    public static class SmsRequestBuilder {
        public SmsRequestBuilder param(String key, Object value) {
            if (this.params == null) {
                this.params = SmsParam.create();
            }
            this.params.add(key, value);
            return this;
        }
    }

    @Override
    public String toString() {
        return "SmsRequest{" +
                "phone='" + DesensitizeUtil.mobilePhone(phone) + '\'' +
                ", phones=" + (phones != null ? phones.stream().map(DesensitizeUtil::mobilePhone).toList() : "[]") +
                ", templateId='" + templateId + '\'' +
                ", params=" + params +
                ", signName='" + signName + '\'' +
                ", channel='" + channel + '\'' +
                ", bizId='" + bizId + '\'' +
                '}';
    }
}
