package io.github.atengk.boot.ai.rag.splitter;

import dev.langchain4j.data.segment.TextSegment;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 父子分级切片聚合结果载体 (ParentChildResult)
 * <p>
 * 封装由 {@link ParentChildSplitter} 一次性切分输出的完整宏观父块、微观子块及多维索引字典，
 * 便于调用方灵活获取父子关联拓扑或分别执行持久化与检索。
 * </p>
 *
 * @param parents            宏观父分块集合
 * @param children           微观子分块集合 (用于向量化索引)
 * @param parentIdToChildren 父分块唯一 ID 到归属微观子分块列表的映射字典
 * @param parentIdToParent   父分块唯一 ID 到宏观父分块实体的映射字典
 * @author Ateng
 * @since 2026-11-05
 */
public record ParentChildResult(
        List<TextSegment> parents,
        List<TextSegment> children,
        Map<String, List<TextSegment>> parentIdToChildren,
        Map<String, TextSegment> parentIdToParent
) {

    public ParentChildResult {
        parents = parents != null ? Collections.unmodifiableList(parents) : Collections.emptyList();
        children = children != null ? Collections.unmodifiableList(children) : Collections.emptyList();
        parentIdToChildren = parentIdToChildren != null ? Collections.unmodifiableMap(parentIdToChildren) : Collections.emptyMap();
        parentIdToParent = parentIdToParent != null ? Collections.unmodifiableMap(parentIdToParent) : Collections.emptyMap();
    }
}
