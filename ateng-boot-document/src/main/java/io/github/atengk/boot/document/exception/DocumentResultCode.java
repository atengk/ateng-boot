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
    FORM_ERROR(57007, "PDF表单域处理异常"),

    /**
     * 不支持动态 XFA 格式表单
     */
    PDF_XFA_UNSUPPORTED(57008, "不支持动态 XFA 格式表单，请先在 Adobe Acrobat 中转换为标准静态 AcroForm 格式"),

    /**
     * PDF 页面索引越界
     */
    PDF_PAGE_INDEX_OUT_OF_BOUNDS(57009, "PDF 页面索引越界"),

    /**
     * PDF 页面渲染超出最大页数安全上限
     */
    PDF_RENDER_EXCEEDED_MAX_PAGES(57010, "PDF 页面渲染超出最大页数安全上限");

    private final Integer code;
    private final String msg;
}
