package io.github.atengk.boot.sample.document.model;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * HTML 片段转 PDF 请求传输对象
 *
 * @author Ateng
 * @since 2026-11-18
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HtmlToPdfRequest {

    /**
     * 文档标题
     */
    @NotBlank(message = "文档标题不能为空")
    private String title;

    /**
     * 待转换的 HTML 源码正文
     */
    @NotBlank(message = "HTML 内容不能为空")
    private String htmlContent;
}
