package io.github.atengk.boot.document.exception;

import io.github.atengk.boot.core.code.IResultCode;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 文档治理模块专属响应状态码枚举
 *
 * @author Ateng
 * @since 2026-10-23
 */
@Getter
@AllArgsConstructor
public enum DocumentResultCode implements IResultCode {

    /**
     * 文档处理操作失败
     */
    DOCUMENT_OPERATION_FAILED(57000, "文档处理操作失败"),

    /**
     * Word 文档模板渲染失败
     */
    WORD_RENDER_FAILED(57001, "Word文档模板渲染失败"),

    /**
     * PDF 处理操作失败
     */
    PDF_OPERATION_FAILED(57002, "PDF处理操作失败"),

    /**
     * 未找到可用的中文字体
     */
    FONT_NOT_FOUND(57003, "未找到可用的中文字体"),

    /**
     * 不支持的文档格式转换
     */
    UNSUPPORTED_CONVERSION(57004, "不支持的文档格式转换"),

    /**
     * Word 模板文件不存在
     */
    TEMPLATE_NOT_FOUND(57005, "Word模板文件不存在"),

    /**
     * PDF 文档密码错误或解密失败
     */
    PASSWORD_ERROR(57006, "PDF文档密码错误或解密失败"),

    /**
     * PDF 表单域处理异常
     */
    FORM_ERROR(57007, "PDF表单域处理异常");

    private final Integer code;
    private final String msg;
}
