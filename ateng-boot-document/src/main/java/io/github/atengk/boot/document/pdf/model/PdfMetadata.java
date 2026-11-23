package io.github.atengk.boot.document.pdf.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * PDF 核心元数据模型（页数、版本、摘要信息与加密权限状态）
 *
 * @author Ateng
 * @since 2026-10-31
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PdfMetadata {

    /**
     * 文档总页数
     */
    private int pageCount;

    /**
     * PDF 规范版本（如 1.4, 1.7, 2.0）
     */
    private float version;

    /**
     * 文档标题
     */
    private String title;

    /**
     * 文档作者
     */
    private String author;

    /**
     * 文档主题
     */
    private String subject;

    /**
     * 关键词标签（以逗号或分号分隔）
     */
    private String keywords;

    /**
     * 原始应用程序（Creator，如 "Microsoft Word"）
     */
    private String creator;

    /**
     * PDF 生产器软件（Producer，如 "Apache PDFBox"）
     */
    private String producer;

    /**
     * 文档创建时间
     */
    private Instant creationDate;

    /**
     * 文档最后修改时间
     */
    private Instant modificationDate;

    /**
     * 是否处于安全加密状态
     */
    private boolean encrypted;

    /**
     * 当前权限：是否允许高保真或降级打印
     */
    private boolean canPrint;

    /**
     * 当前权限：是否允许修改文档内容或旋转页面
     */
    private boolean canModify;

    /**
     * 当前权限：是否允许抽取正文内容
     */
    private boolean canExtractContent;

    /**
     * 当前权限：是否允许复制正文或图片内容
     */
    private boolean canCopy;

    /**
     * 当前权限：是否允许拼接插入页面
     */
    private boolean canAssembleDocument;

    /**
     * 当前权限：是否允许填充表单字段
     */
    private boolean canFillInForm;

    /**
     * 是否包含 AcroForm 交互式表单
     */
    private boolean hasAcroForm;

    /**
     * 是否包含动态 XFA (XML Forms Architecture)
     */
    private boolean hasXfa;
}
