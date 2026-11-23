package io.github.atengk.boot.web.captcha.enums;

/**
 * 随机字符图形验证码字符集策略枚举
 *
 * @author Ateng
 * @since 2026-11-17
 */
public enum CaptchaCharType {

    /**
     * 安全字母数字混合（默认，剔除易混淆字符 0/O/o/1/I/l）
     */
    ALPHANUMERIC_SAFE("23456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz"),

    /**
     * 纯数字字符集
     */
    NUMERIC("0123456789"),

    /**
     * 纯英文字母字符集（大小写）
     */
    ALPHA("ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"),

    /**
     * 全量数字与大小写字母混合
     */
    ALPHANUMERIC("0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz");

    private final String alphabet;

    CaptchaCharType(String alphabet) {
        this.alphabet = alphabet;
    }

    /**
     * 获取当前策略对应的候选字符表
     *
     * @return 候选字符集合字符串
     */
    public String getAlphabet() {
        return alphabet;
    }
}
