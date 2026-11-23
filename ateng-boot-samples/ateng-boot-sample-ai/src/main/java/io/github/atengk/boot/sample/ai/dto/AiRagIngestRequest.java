package io.github.atengk.boot.sample.ai.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.util.Map;

/**
 * 知识库切片入库请求 DTO
 *
 * @author Ateng
 * @since 2026-11-04
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiRagIngestRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 待存储的知识内容文本
     */
    @NotBlank(message = "待存储知识内容不能为空")
    private String text;

    /**
     * 业务切片元数据 (如 category, source, docId 等)
     */
    private Map<String, String> metadata;
}
