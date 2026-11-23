package io.github.atengk.boot.sample.document.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * PDF 文档元数据增量更新请求传输对象
 *
 * @author Ateng
 * @since 2026-11-18
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PdfMetadataUpdateRequest {

    /**
     * 文档标题
     */
    @NotBlank(message = "文档标题不能为空")
    @Size(max = 256, message = "文档标题长度不能超过 256 个字符")
    private String title;

    /**
     * 文档作者
     */
    @Size(max = 128, message = "文档作者长度不能超过 128 个字符")
    private String author;

    /**
     * 文档主题
     */
    @Size(max = 256, message = "文档主题长度不能超过 256 个字符")
    private String subject;

    /**
     * 关键词标签
     */
    @Size(max = 256, message = "关键词长度不能超过 256 个字符")
    private String keywords;

    /**
     * 原始创建工具名称
     */
    @Size(max = 128, message = "创建工具名称长度不能超过 128 个字符")
    private String creator;

    /**
     * 打开加密 PDF 文档的密码（可选）
     */
    private String password;
}
