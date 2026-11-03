package io.github.atengk.boot.sms.model;

import io.github.atengk.boot.core.util.BusinessAssert;
import io.github.atengk.boot.core.util.DesensitizeUtil;
import lombok.Builder;
import lombok.Getter;

import java.io.Serializable;

/**
 * 单条短信发送领域请求对象
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
     * 目标接收人手机号（支持国内 11 位或带国际区号 E.164 格式）
     */
    private final String phone;

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

    /**
     * 构造并实施前置契约校验
     *
     * @param phone      手机号
     * @param templateId 模板ID
     * @param params     参数
     * @param signName   签名
     * @param channel    渠道
     * @param bizId      业务流水号
     */
    public SmsRequest(String phone, String templateId, SmsParam params, String signName, String channel, String bizId) {
        BusinessAssert.hasText(phone, "目标手机号不能为空");
        BusinessAssert.hasText(templateId, "短信模板编码不能为空");
        this.phone = phone.trim();
        this.templateId = templateId.trim();
        this.params = params != null ? params : SmsParam.create();
        this.signName = signName != null ? signName.trim() : null;
        this.channel = channel != null ? channel.trim() : null;
        this.bizId = bizId != null ? bizId.trim() : null;
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
     * 便捷静态工厂：单号码与模板
     *
     * @param phone      目标手机号
     * @param templateId 模板编码
     * @return SmsRequest 实例
     */
    public static SmsRequest of(String phone, String templateId) {
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
        return builder().phone(phone).templateId(templateId).params(params).build();
    }

    @Override
    public String toString() {
        return "SmsRequest{" +
                "phone='" + DesensitizeUtil.mobilePhone(phone) + '\'' +
                ", templateId='" + templateId + '\'' +
                ", params=" + params +
                ", signName='" + signName + '\'' +
                ", channel='" + channel + '\'' +
                ", bizId='" + bizId + '\'' +
                '}';
    }
}
