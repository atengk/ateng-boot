package io.github.atengk.boot.sample.ai.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * AI 对话请求传输对象 DTO
 *
 * @author Ateng
 * @since 2026-11-03
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiChatRequestDto implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 用户提示词或问题内容
     */
    @NotBlank(message = "用户提示词 prompt 不能为空")
    private String prompt;

    /**
     * 目标大模型名称或别名 (可选，为空时自动回退为默认模型)
     */
    private String model;

    /**
     * 图片 URL 或 Base64 数据列表 (可选，用于多模态视觉理解)
     */
    private List<String> images;
}
