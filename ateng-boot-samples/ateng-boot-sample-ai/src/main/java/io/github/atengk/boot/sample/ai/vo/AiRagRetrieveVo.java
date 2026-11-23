package io.github.atengk.boot.sample.ai.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 知识库检索召回结果 VO
 *
 * @author Ateng
 * @since 2026-11-04
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiRagRetrieveVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 检索提问
     */
    private String query;

    /**
     * 召回命中的知识片段列表
     */
    @Builder.Default
    private List<MatchItem> matches = Collections.emptyList();

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MatchItem implements Serializable {

        @Serial
        private static final long serialVersionUID = 1L;

        /**
         * 命中内容文本
         */
        private String text;

        /**
         * 命中内容关联元数据
         */
        private Map<String, String> metadata;
    }
}
