package io.github.atengk.boot.ai.rag.citation;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 可信 RAG 证据溯源强类型出参契约
 * 包含生成回答正文、结构化引用证据切片清单与事实忠实度评分
 *
 * @param <T> 结果载荷泛型 (通常为 String 或结构化业务实体)
 * @author Ateng
 * @since 2026-11-06
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RagResponse<T> implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 生成的回答内容或结构化载荷
     */
    private T result;

    /**
     * 结构化引用切片证据清单 (有序且已对齐清洗)
     */
    @Builder.Default
    private List<Citation> citations = Collections.emptyList();

    /**
     * 事实忠实度 / 证据可信度评分 (Grounding Score, 0.0 ~ 1.0)
     */
    @Builder.Default
    private Double groundingScore = 1.0;

    public List<Citation> getCitations() {
        return citations != null ? citations : Collections.emptyList();
    }

    public void setCitations(List<Citation> citations) {
        this.citations = citations != null ? new ArrayList<>(citations) : Collections.emptyList();
    }

    /**
     * 快捷构建纯结果响应 (引用列表为空，忠实度默认 1.0)
     *
     * @param result 结果内容
     * @param <T>    泛型类型
     * @return RagResponse 实例
     */
    public static <T> RagResponse<T> of(T result) {
        return of(result, Collections.emptyList(), 1.0);
    }

    /**
     * 快捷构建带引用清单响应 (忠实度默认 1.0)
     *
     * @param result    结果内容
     * @param citations 引用清单
     * @param <T>       泛型类型
     * @return RagResponse 实例
     */
    public static <T> RagResponse<T> of(T result, List<Citation> citations) {
        return of(result, citations, 1.0);
    }

    /**
     * 快捷构建带引用清单与事实忠实度评分的响应
     *
     * @param result         结果内容
     * @param citations      引用清单
     * @param groundingScore 事实忠实度评分 (0.0 ~ 1.0)
     * @param <T>            泛型类型
     * @return RagResponse 实例
     */
    public static <T> RagResponse<T> of(T result, List<Citation> citations, Double groundingScore) {
        return RagResponse.<T>builder()
                .result(result)
                .citations(citations != null ? citations : Collections.emptyList())
                .groundingScore(groundingScore != null ? groundingScore : 1.0)
                .build();
    }

    /**
     * 构建空响应
     *
     * @param <T> 泛型类型
     * @return 空 RagResponse 实例
     */
    public static <T> RagResponse<T> empty() {
        return of(null, Collections.emptyList(), 0.0);
    }
}
