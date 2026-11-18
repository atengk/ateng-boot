package io.github.atengk.boot.document.pdf.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * PDF 文档元数据增量更新配置模型（仅覆写非 null 字段，设为空字符串可用于清理单项字段）
 *
 * @author Ateng
 * @since 2026-11-18
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PdfMetadataUpdate {

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
     * 关键词
     */
    private String keywords;

    /**
     * 原始创建工具/程序名称
     */
    private String creator;

    /**
     * PDF 生成器名称
     */
    private String producer;

    /**
     * 创建时间
     */
    private Instant creationDate;

    /**
     * 最后修改时间
     */
    private Instant modificationDate;

    /**
     * 打开加密 PDF 文档的密码（可选）
     */
    private String password;
}
