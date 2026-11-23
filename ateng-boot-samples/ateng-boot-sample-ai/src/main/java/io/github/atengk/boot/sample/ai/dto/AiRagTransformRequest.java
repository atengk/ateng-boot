package io.github.atengk.boot.sample.ai.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.Collections;
import java.util.List;

/**
 * RAG 多轮问答查询改写请求传输对象
 *
 * @author Ateng
 * @since 2026-11-05
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiRagTransformRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 用户最新问句 (待改写消解代词的问题)
     */
    @NotBlank(message = "用户最新问句 query 不能为空")
    private String query;

    /**
     * 前序多轮历史问答对话记录列表 (按时间正序排列)
     */
    @Builder.Default
    private List<String> history = Collections.emptyList();
}
