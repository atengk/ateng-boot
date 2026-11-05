package io.github.atengk.boot.sample.ai.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * 父子分级分块切片入参
 *
 * @author Ateng
 * @since 2026-11-05
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiRagParentChildSplitRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 待分块切片的长文本内容
     */
    @NotBlank(message = "待切片文本不能为空")
    private String text;

    /**
     * 宏观父分块字符大小 (默认 1200)
     */
    private Integer parentChunkSize;

    /**
     * 微观子分块字符大小 (默认 300)
     */
    private Integer childChunkSize;
}
