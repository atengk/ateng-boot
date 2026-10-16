package io.github.atengk.boot.excel.enums;

import io.github.atengk.boot.core.util.DesensitizeUtil;
import io.github.atengk.boot.excel.annotation.ExcelSensitive;

import java.util.function.Function;

/**
 * Excel 导出敏感数据脱敏类型枚举
 * <p>
 * 配合 {@link io.github.atengk.boot.excel.annotation.ExcelSensitive} 注解使用，
 * 内部映射 {@link DesensitizeUtil} 高性能纯 JDK 脱敏算法。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-16
 */
public enum DesensitizeType {

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
     * 首尾保留掩码（由 keepHead、keepTail 与 maskChar 决定）
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

    DesensitizeType(Function<String, String> maskFunction) {
        this.maskFunction = maskFunction;
    }

    /**
     * 基于注解定义执行脱敏掩码转换
     *
     * @param value     原始待脱敏字符串
     * @param sensitive 脱敏注解配置
     * @return 掩码后的字符串
     */
    public static String mask(String value, ExcelSensitive sensitive) {
        if (value == null || value.isEmpty() || sensitive == null) {
            return value;
        }

        // 1. 确定生效的脱敏类型（type 优先，其次 value）
        DesensitizeType targetType = sensitive.type();
        if (targetType == CUSTOM && sensitive.value() != CUSTOM) {
            targetType = sensitive.value();
        }

        // 2. 自动类型推导：若未显式指定预设类型，但配置了首尾保留字符数，智能转为 KEEP_HEAD_TAIL
        if (targetType == CUSTOM && (sensitive.keepHead() > 0 || sensitive.keepTail() > 0)) {
            targetType = KEEP_HEAD_TAIL;
        }

        // 3. 执行掩码
        return targetType.mask(
                value,
                sensitive.startInclude(),
                sensitive.endExclude(),
                sensitive.keepHead(),
                sensitive.keepTail(),
                sensitive.maskChar()
        );
    }

    /**
     * 执行脱敏掩码转换
     *
     * @param value        原始待脱敏字符串
     * @param startInclude 自定义起始包含索引（仅 CUSTOM 生效）
     * @param endExclude   自定义结束排除索引（仅 CUSTOM 生效）
     * @param keepHead     头部保留字符数（仅 KEEP_HEAD_TAIL 生效）
     * @param keepTail     尾部保留字符数（仅 KEEP_HEAD_TAIL 生效）
     * @param maskChar     用于替换的掩码字符
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
