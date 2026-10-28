package io.github.atengk.boot.sample.web.dto;

import io.github.atengk.boot.web.xss.annotation.XssIgnore;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 演示文章发布入参 DTO
 * 演示普通字段（自动执行 XSS 清洗）与字段级 {@link XssIgnore} 豁免（保留原始富文本 HTML/Markdown）
 *
 * @author Ateng
 * @since 2026-10-27
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ArticlePublishDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 文章标题（普通字段：自动进行 XSS 安全转义或剔除）
     */
    @NotBlank(message = "文章标题不能为空")
    private String title;

    /**
     * 文章正文（富文本/Markdown 字段：显式使用 @XssIgnore 豁免清洗，保留原样 HTML）
     */
    @NotBlank(message = "文章正文不能为空")
    @XssIgnore
    private String content;

    /**
     * 作者名称（普通字段：自动进行 XSS 安全转义或剔除）
     */
    private String author;
}
