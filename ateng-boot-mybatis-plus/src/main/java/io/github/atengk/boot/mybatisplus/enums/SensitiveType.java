package io.github.atengk.boot.mybatisplus.enums;

import io.github.atengk.boot.core.util.DesensitizeUtil;

import java.util.function.Function;

/**
 * 敏感数据脱敏类型枚举
 * 配合 @FieldSensitive 注解使用，内聚映射 DesensitizeUtil 脱敏算法
 *
 * @author Ateng
 * @since 2026-10-16
 */
public enum SensitiveType {

    /**
     * 中文姓名（如：张*、李*四、诸**明）
     */
    CHINESE_NAME(DesensitizeUtil::chineseName),

    /**
     * 手机号码（如：138****5678）
     */
    MOBILE_PHONE(DesensitizeUtil::mobilePhone),

    /**
     * 固定电话（如：010-******78、0755-******99）
     */
    FIXED_PHONE(DesensitizeUtil::fixedPhone),

    /**
     * 身份证号码（如：110101********2345）
     */
    ID_CARD(DesensitizeUtil::idCard),

    /**
     * 电子邮箱（如：z******n@example.com）
     */
    EMAIL(DesensitizeUtil::email),

    /**
     * 银行卡号（如：622202*********0123）
     */
    BANK_CARD(DesensitizeUtil::bankCard),

    /**
     * 密码（如：******）
     */
    PASSWORD(DesensitizeUtil::password),

    /**
     * 自定义区间掩码（由 startInclude、endExclude 与 maskChar 决定）
     */
    CUSTOM(null),

    /**
     * 不脱敏（原样输出）
     */
    NONE(Function.identity());

    private final Function<String, String> maskFunction;

    SensitiveType(Function<String, String> maskFunction) {
        this.maskFunction = maskFunction;
    }

    /**
     * 执行脱敏掩码转换
     *
     * @param value        原始待脱敏字符串
     * @param startInclude 自定义起始包含索引（仅 CUSTOM 生效）
     * @param endExclude   自定义结束排除索引（仅 CUSTOM 生效）
     * @param maskChar     自定义掩码字符（仅 CUSTOM 生效）
     * @return 掩码后的字符串
     */
    public String mask(String value, int startInclude, int endExclude, char maskChar) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        if (this == CUSTOM) {
            return DesensitizeUtil.mask(value, startInclude, endExclude, maskChar);
        }
        return maskFunction != null ? maskFunction.apply(value) : value;
    }
}
