package io.github.atengk.boot.core.util;

/**
 * 敏感数据通用脱敏工具类
 * 基于 Zero-Bloat 原则提供高性能、纯 JDK 实现的全场景数据动态掩码能力
 *
 * @author Ateng
 * @since 2026-10-16
 */
public final class DesensitizeUtil {

    private static final char DEFAULT_MASK_CHAR = '*';
    private static final String DEFAULT_PASSWORD_MASK = "******";

    private DesensitizeUtil() {
    }

    /**
     * 通用字符串指定区间字符掩码
     *
     * @param str          待脱敏原始字符串
     * @param startInclude 掩码起始索引（包含，从 0 开始；小于 0 则自动按 0 处理）
     * @param endExclude   掩码结束索引（不包含；大于字符串长度则自动按长度处理）
     * @param maskChar     用于替换的掩码字符（如 '*'）
     * @return 脱敏后的字符串；若原始字符串为 null 则返回 null，若为空串返回空串
     */
    public static String mask(String str, int startInclude, int endExclude, char maskChar) {
        if (str == null) {
            return null;
        }
        int len = str.length();
        if (len == 0) {
            return "";
        }

        int start = Math.max(0, startInclude);
        int end = Math.min(len, endExclude);
        if (start >= end || start >= len) {
            return str;
        }

        char[] chars = str.toCharArray();
        for (int i = start; i < end; i++) {
            chars[i] = maskChar;
        }
        return new String(chars);
    }

    /**
     * 通用字符串指定区间字符掩码（默认使用 '*' 掩码字符）
     *
     * @param str          待脱敏原始字符串
     * @param startInclude 掩码起始索引（包含）
     * @param endExclude   掩码结束索引（不包含）
     * @return 脱敏后的字符串
     */
    public static String mask(String str, int startInclude, int endExclude) {
        return mask(str, startInclude, endExclude, DEFAULT_MASK_CHAR);
    }

    /**
     * 中文姓名脱敏
     * 规则：单字姓名不脱敏；两字姓名脱敏后一字（如“张*”）；三字及以上保留首尾字，中间全部打码（如“李*四”、“诸**明”）
     *
     * @param name 中文姓名
     * @return 脱敏后的姓名
     */
    public static String chineseName(String name) {
        if (name == null || name.isEmpty()) {
            return name;
        }
        int len = name.length();
        if (len <= 1) {
            return name;
        }
        if (len == 2) {
            return mask(name, 1, 2);
        }
        return mask(name, 1, len - 1);
    }

    /**
     * 手机号码脱敏（保留前 3 位与后 4 位，中间打码）
     * 例如："13812345678" -> "138****5678"
     *
     * @param phone 手机号码
     * @return 脱敏后的手机号码
     */
    public static String mobilePhone(String phone) {
        if (phone == null || phone.isEmpty()) {
            return phone;
        }
        int len = phone.length();
        if (len <= 7) {
            return mask(phone, Math.min(1, len), Math.max(1, len - 1));
        }
        return mask(phone, 3, len - 4);
    }

    /**
     * 固定电话脱敏（有分号时保留分号及之前区号，保留后 2 位，中间打码；无分号时保留前 4 位区号与后 2 位，中间打码）
     * 例如："010-12345678" -> "010-******78"，"0755-88889999" -> "0755-******99"
     *
     * @param phone 固定电话
     * @return 脱敏后的固定电话
     */
    public static String fixedPhone(String phone) {
        if (phone == null || phone.isEmpty()) {
            return phone;
        }
        int len = phone.length();
        int hyphenIndex = phone.indexOf('-');
        int start = hyphenIndex > 0 ? hyphenIndex + 1 : Math.min(4, len);
        int end = Math.max(start, len - 2);
        return mask(phone, start, end);
    }

    /**
     * 身份证号码脱敏（保留前 6 位与后 4 位，中间打码）
     * 例如："110101199003072345" -> "110101********2345"
     *
     * @param idCard 身份证号码
     * @return 脱敏后的身份证号码
     */
    public static String idCard(String idCard) {
        if (idCard == null || idCard.isEmpty()) {
            return idCard;
        }
        int len = idCard.length();
        if (len <= 10) {
            return mask(idCard, Math.min(2, len), Math.max(2, len - 2));
        }
        return mask(idCard, 6, len - 4);
    }

    /**
     * 电子邮箱脱敏
     * 规则：保留前 1 位和 '@' 前最后 1 位，中间打码；若用户名较短则做防御性打码
     * 例如："zhangsan@example.com" -> "z******n@example.com"
     *
     * @param email 电子邮箱
     * @return 脱敏后的电子邮箱
     */
    public static String email(String email) {
        if (email == null || email.isEmpty()) {
            return email;
        }
        int atIndex = email.indexOf('@');
        if (atIndex <= 1) {
            return email;
        }
        if (atIndex == 2) {
            return mask(email, 1, 2);
        }
        return mask(email, 1, atIndex - 1);
    }

    /**
     * 银行卡号脱敏（保留前 6 位与后 4 位，中间打码）
     * 例如："6222021234567890123" -> "622202*********0123"
     *
     * @param card 银行卡号
     * @return 脱敏后的银行卡号
     */
    public static String bankCard(String card) {
        if (card == null || card.isEmpty()) {
            return card;
        }
        int len = card.length();
        if (len <= 10) {
            return mask(card, Math.min(2, len), Math.max(2, len - 2));
        }
        return mask(card, 6, len - 4);
    }

    /**
     * 密码脱敏（返回固定 6 位 '*'，防止暴露密码实际字符长度）
     *
     * @param password 原始密码
     * @return 脱敏后的密码占位符（默认 "******"）
     */
    public static String password(String password) {
        return password(password, DEFAULT_PASSWORD_MASK);
    }

    /**
     * 密码自定义占位符脱敏
     *
     * @param password 原始密码
     * @param mask     自定义密码掩码占位符（如 "######"）；若为 null 则使用默认 "******"
     * @return 脱敏后的密码占位符
     */
    public static String password(String password, String mask) {
        if (password == null) {
            return null;
        }
        if (password.isEmpty()) {
            return "";
        }
        return mask != null ? mask : DEFAULT_PASSWORD_MASK;
    }
}
