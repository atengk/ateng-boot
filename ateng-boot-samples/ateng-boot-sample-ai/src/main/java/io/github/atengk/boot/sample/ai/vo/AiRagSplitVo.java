package io.github.atengk.boot.sample.ai.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 文本智能切片响应视图对象
 *
 * @author Ateng
 * @since 2026-11-05
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiRagSplitVo implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 切片总数
     */
    private int totalSegments;

    /**
     * 所采用的切片策略
     */
    private String strategy;

    /**
     * 切片列表
     */
    @Builder.Default
    private List<SegmentItem> segments = Collections.emptyList();

    /**
     * 单个切片详情
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SegmentItem implements Serializable {

        private static final long serialVersionUID = 1L;

        /**
         * 切片索引位置
         */
        private int index;

        /**
         * 切片文本内容
         */
        private String text;

        /**
         * 切片字符长度
         */
        private int characterCount;

        /**
         * 结构化元数据 (如 Markdown 标题层级等)
         */
        @Builder.Default
        private Map<String, Object> metadata = Collections.emptyMap();
    }
}
