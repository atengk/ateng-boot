package io.github.atengk.boot.liteflow.core;

import java.io.Serializable;
import java.util.Objects;

/**
 * LiteFlow EL 规则表达式语法结构化校验结果
 *
 * @author Ateng
 * @since 2026-11-06
 */
public class LiteflowElValidationResult implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 表达式语法是否合法
     */
    private final boolean valid;

    /**
     * 被校验的原始 EL 表达式
     */
    private final String el;

    /**
     * 语法校验失败诊断信息（校验通过时为 null）
     */
    private final String errorMessage;

    public LiteflowElValidationResult(boolean valid, String el, String errorMessage) {
        this.valid = valid;
        this.el = el;
        this.errorMessage = errorMessage;
    }

    /**
     * 构建校验成功结果
     *
     * @param el 原始 EL 表达式
     * @return 成功结果实例
     */
    public static LiteflowElValidationResult success(String el) {
        return new LiteflowElValidationResult(true, el, null);
    }

    /**
     * 构建校验失败结果
     *
     * @param el           原始 EL 表达式
     * @param errorMessage 错误原因
     * @return 失败结果实例
     */
    public static LiteflowElValidationResult fail(String el, String errorMessage) {
        return new LiteflowElValidationResult(false, el, errorMessage);
    }

    /**
     * 获取语法是否合法
     *
     * @return true 表示合法有效，false 表示存在语法或语义错误
     */
    public boolean isValid() {
        return valid;
    }

    /**
     * 获取被校验的原始 EL 表达式
     *
     * @return 原始 EL 表达式字符串
     */
    public String getEl() {
        return el;
    }

    /**
     * 获取校验失败诊断信息
     *
     * @return 错误原因；校验成功时返回 null
     */
    public String getErrorMessage() {
        return errorMessage;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        LiteflowElValidationResult that = (LiteflowElValidationResult) o;
        return valid == that.valid && Objects.equals(el, that.el) && Objects.equals(errorMessage, that.errorMessage);
    }

    @Override
    public int hashCode() {
        return Objects.hash(valid, el, errorMessage);
    }

    @Override
    public String toString() {
        return "LiteflowElValidationResult{" +
                "valid=" + valid +
                ", el='" + el + '\'' +
                ", errorMessage='" + errorMessage + '\'' +
                '}';
    }
}
