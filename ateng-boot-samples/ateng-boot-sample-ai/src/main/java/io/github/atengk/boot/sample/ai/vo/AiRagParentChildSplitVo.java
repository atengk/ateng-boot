package io.github.atengk.boot.sample.ai.vo;

import lombok.Builder;
import lombok.Value;

import java.util.List;

/**
 * 父子分级分块切片响应载体
 *
 * @author Ateng
 * @since 2026-11-05
 */
@Value
@Builder
public class AiRagParentChildSplitVo {

    /**
     * 宏观父分块总数
     */
    int parentCount;

    /**
     * 微观子分块总数
     */
    int childCount;

    /**
     * 父分块结构详情列表
     */
    List<ParentChunkItem> parents;

    /**
     * 宏观父分块项详情
     */
    @Value
    @Builder
    public static class ParentChunkItem {
        String parentId;
        int characterCount;
        String textPreview;
        List<String> childIds;
    }
}
