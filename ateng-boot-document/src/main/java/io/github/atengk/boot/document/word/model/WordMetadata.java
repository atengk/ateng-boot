package io.github.atengk.boot.document.word.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Word (.docx) 文档结构与安全元数据自省模型
 *
 * @author Ateng
 * @since 2026-11-18
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WordMetadata {

    /**
     * 页数提示（源自扩展属性 app.xml Pages，未经过桌面排版计算时可能为 0）
     */
    private int pageCountHint;

    /**
     * 正文与表格单元格内的段落总数
     */
    private int paragraphCount;

    /**
     * 文档顶层表格总数
     */
    private int tableCount;

    /**
     * 文档内嵌图片资源总数
     */
    private int pictureCount;

    /**
     * 是否包含页眉或页脚部件
     */
    private boolean hasHeadersOrFooters;

    /**
     * 是否已施加 settings.xml 只读编辑锁定（ReadonlyProtection）
     */
    private boolean readonlyProtected;

    /**
     * 是否处于文件打开级 Agile AES-256（OLE2）加密信封保护状态
     */
    private boolean encrypted;

    /**
     * 文档标题
     */
    private String title;

    /**
     * 文档作者（Creator）
     */
    private String author;

    /**
     * 文档主题
     */
    private String subject;

    /**
     * 关键词标签
     */
    private String keywords;

    /**
     * 生成应用程序名称（Application）
     */
    private String creator;

    /**
     * 文档创建时间
     */
    private Instant creationDate;

    /**
     * 文档最后修改时间
     */
    private Instant modificationDate;
}
