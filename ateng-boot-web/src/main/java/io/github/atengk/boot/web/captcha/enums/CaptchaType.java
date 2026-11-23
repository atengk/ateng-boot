package io.github.atengk.boot.web.captcha.enums;

/**
 * 图形验证码内容模式枚举
 *
 * @author Ateng
 * @since 2026-11-17
 */
public enum CaptchaType {

    /**
     * 随机字符模式
     */
    CHAR,

    /**
     * 简单算术题模式（展示表达式，校验计算结果）
     */
    MATH
}
