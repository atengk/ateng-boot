package io.github.atengk.boot.sms.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import io.github.atengk.boot.core.enums.IBaseEnum;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.Arrays;

/**
 * 短信投递状态枚举
 *
 * @author Ateng
 * @since 2026-11-02
 */
@Getter
@AllArgsConstructor
public enum SmsDeliveryStatus implements IBaseEnum<String> {

    /**
     * 云厂商已受理
     */
    ACCEPTED("ACCEPTED", "已受理"),

    /**
     * 运营商已送达
     */
    DELIVERED("DELIVERED", "已送达"),

    /**
     * 发送失败
     */
    FAILED("FAILED", "发送失败"),

    /**
     * 出站前置拦截（黑名单或预算熔断）
     */
    BLOCKED("BLOCKED", "出站拦截"),

    /**
     * 本地沙箱模拟成功
     */
    MOCKED("MOCKED", "沙箱模拟");

    @JsonValue
    private final String code;

    private final String label;

    /**
     * 根据编码安全解析状态枚举
     *
     * @param code 状态码
     * @return 匹配的枚举对象，无匹配时返回 null
     */
    @JsonCreator
    public static SmsDeliveryStatus fromCode(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        return Arrays.stream(values())
                .filter(item -> item.code.equalsIgnoreCase(code.trim()) || item.name().equalsIgnoreCase(code.trim()))
                .findFirst()
                .orElse(null);
    }
}
