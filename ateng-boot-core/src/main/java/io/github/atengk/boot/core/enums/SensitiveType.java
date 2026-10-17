package io.github.atengk.boot.core.enums;

import io.github.atengk.boot.core.util.DesensitizeUtil;

import java.util.function.Function;

/**
 * 敏感数据脱敏类型枚举
 * 配合 @FieldSensitive 与 @ExcelSensitive 注解使用，内聚映射 DesensitizeUtil 脱敏算法
 *
 * @author Ateng
 * @since 2026-10-17
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
     * 国内地址（如：北京市海淀区*********）
     */
    ADDRESS(DesensitizeUtil::address),

    /**
     * 车牌号码（如：京A***88、粤B****45）
     */
    CAR_LICENSE(DesensitizeUtil::carLicense),

    /**
     * IPv4 地址（如：192.168.1.*）
     */
    IPV4(DesensitizeUtil::ipv4),

    /**
     * IPv6 地址（如：2001:****:7334）
     */
    IPV6(DesensitizeUtil::ipv6),

    /**
     * 统一社会信用代码（如：91************BC12）
     */
    SOCIAL_CREDIT_CODE(DesensitizeUtil::socialCreditCode),

    /**
     * 首尾保留掩码（由 keepHead、keepTail 与 maskChar 决定，超长防御原样返回）
     */
    KEEP_HEAD_TAIL(null),

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
     * 执行脱敏掩码转换（兼容旧版入参）
     *
     * @param value        原始待脱敏字符串
     * @param startInclude 自定义起始包含索引（仅 CUSTOM 生效）
     * @param endExclude   自定义结束排除索引（仅 CUSTOM 生效）
     * @param maskChar     自定义掩码字符（仅 CUSTOM 生效）
     * @return 掩码后的字符串
     */
    public String mask(String value, int startInclude, int endExclude, char maskChar) {
        return mask(value, startInclude, endExclude, 0, 0, maskChar);
    }

    /**
     * 执行脱敏掩码转换（支持首尾保留参数）
     *
     * @param value        原始待脱敏字符串
     * @param startInclude 自定义起始包含索引（仅 CUSTOM 生效）
     * @param endExclude   自定义结束排除索引（仅 CUSTOM 生效）
     * @param keepHead     头部保留字符数（仅 KEEP_HEAD_TAIL 生效）
     * @param keepTail     尾部保留字符数（仅 KEEP_HEAD_TAIL 生效）
     * @param maskChar     自定义掩码字符（CUSTOM 与 KEEP_HEAD_TAIL 生效）
     * @return 掩码后的字符串
     */
    public String mask(String value, int startInclude, int endExclude, int keepHead, int keepTail, char maskChar) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        if (this == CUSTOM) {
            return DesensitizeUtil.mask(value, startInclude, endExclude, maskChar);
        }
        if (this == KEEP_HEAD_TAIL) {
            return DesensitizeUtil.maskKeepHeadTail(value, keepHead, keepTail, maskChar);
        }
        return maskFunction != null ? maskFunction.apply(value) : value;
    }
}
