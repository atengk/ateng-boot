package io.github.atengk.boot.sms.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import io.github.atengk.boot.core.enums.IBaseEnum;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.Arrays;

/**
 * 短信服务商标识枚举
 *
 * @author Ateng
 * @since 2026-11-02
 */
@Getter
@AllArgsConstructor
public enum SmsSupplier implements IBaseEnum<String> {

    /**
     * 阿里云短信 (Dysmsapi)
     */
    ALIYUN("aliyun", "阿里云短信"),

    /**
     * 腾讯云短信 (SMS 3.0)
     */
    TENCENT("tencent", "腾讯云短信"),

    /**
     * 华为云消息&短信 (MSGSMS)
     */
    HUAWEI("huawei", "华为云短信"),

    /**
     * 云片网短信 (Yunpian)
     */
    YUNPIAN("yunpian", "云片网短信"),

    /**
     * 天翼云短信 (CTYun)
     */
    CTYUN("ctyun", "天翼云短信"),

    /**
     * 本地测试内存沙箱
     */
    MOCK("mock", "本地Mock沙箱"),

    /**
     * 自定义扩展厂商
     */
    CUSTOM("custom", "自定义厂商");

    @JsonValue
    private final String code;

    private final String label;

    /**
     * 获取云厂商单次 API 调用支持的最大手机号切片上限
     *
     * @return 单批最大手机号数量
     */
    public int getBatchLimit() {
        return switch (this) {
            case TENCENT -> 200;
            case HUAWEI -> 500;
            case ALIYUN, YUNPIAN, CTYUN, MOCK, CUSTOM -> 1000;
        };
    }

    /**
     * 根据编码安全解析短信服务商枚举（不区分大小写）
     *
     * @param code 服务商编码
     * @return 匹配的枚举对象，无匹配时返回 null
     */
    @JsonCreator
    public static SmsSupplier fromCode(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        return Arrays.stream(values())
                .filter(item -> item.code.equalsIgnoreCase(code.trim()) || item.name().equalsIgnoreCase(code.trim()))
                .findFirst()
                .orElse(null);
    }
}
