package io.github.atengk.boot.sample.ai.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * 多源异构文档解析入参
 *
 * @author Ateng
 * @since 2026-11-05
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiRagParseRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 文档文件名 (如 guide.md, report.html, spec.txt 等，用于自动嗅探文档格式)
     */
    @NotBlank(message = "文档文件名不能为空")
    private String filename;

    /**
     * 文档文本内容或 Base64 编码数据
     */
    @NotBlank(message = "文档文本内容不能为空")
    private String content;

    /**
     * 是否启用 OCR 扫描件字符识别增强 (针对图片/扫描版文档)
     */
    private Boolean enableOcr;

    /**
     * 是否启用视觉多模态图表摘要增强
     */
    private Boolean enableVisionSummary;
}
