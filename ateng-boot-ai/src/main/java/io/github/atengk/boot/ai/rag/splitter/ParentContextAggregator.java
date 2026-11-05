package io.github.atengk.boot.ai.rag.splitter;

import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 原生父上下文重组与召回聚合器 (ParentContextAggregator)
 * <p>
 * 在 RAG 知识库检索阶段，当多个命中的微观子分块属于同一个宏观父分块时，
 * 本工具负责自动执行 ID 去重、按文档原始位置排序并还原为完整、未被碎片化的宏观父级上下文，
 * 最大化喂给大语言模型 (LLM) 的上下文完整性与连贯性。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-05
 */
public final class ParentContextAggregator {

    public static final String DEFAULT_DELIMITER = "\n\n---\n\n";

    private ParentContextAggregator() {
        // 工具类禁止外部实例化
    }

    /**
     * 将检索命中的微观子片段列表自动去重并还原为宏观父分块文本列表
     * <p>
     * 具备自动退化容错：对于未携带 {@code parent_id} 或 {@code parent_text} 的普通切片，平滑降级采用切片自身文本。
     * </p>
     *
     * @param segments 检索命中的 TextSegment 集合
     * @return 还原并去重后的父分块文本列表（按原始文档出现位置排序，绝不返回 null）
     */
    public static List<String> reconstructParentTexts(List<TextSegment> segments) {
        if (segments == null || segments.isEmpty()) {
            return Collections.emptyList();
        }

        List<TextSegment> reconstructedSegments = reconstructParentSegments(segments);
        List<String> texts = new ArrayList<>(reconstructedSegments.size());
        for (TextSegment seg : reconstructedSegments) {
            if (seg != null && seg.text() != null && !seg.text().isBlank()) {
                texts.add(seg.text());
            }
        }
        return Collections.unmodifiableList(texts);
    }

    /**
     * 将检索命中的微观子片段列表重组为宏观父分块 TextSegment 集合
     * <p>
     * 自动聚合统计指标（如 {@code matched_child_count}），并维持父分块元数据完整性。
     * </p>
     *
     * @param segments 检索命中的 TextSegment 集合
     * @return 包含聚合元数据的重构父分块列表（绝不返回 null）
     */
    public static List<TextSegment> reconstructParentSegments(List<TextSegment> segments) {
        if (segments == null || segments.isEmpty()) {
            return Collections.emptyList();
        }

        // parentId -> 聚合载体
        Map<String, ParentAggregateEntry> parentMap = new LinkedHashMap<>();
        List<TextSegment> nonParentSegments = new ArrayList<>();

        for (int i = 0; i < segments.size(); i++) {
            TextSegment child = segments.get(i);
            if (child == null) {
                continue;
            }

            Metadata meta = child.metadata();
            String parentId = meta != null ? meta.getString("parent_id") : null;
            String parentText = meta != null ? meta.getString("parent_text") : null;

            if (parentId != null && !parentId.isBlank() && parentText != null && !parentText.isBlank()) {
                Integer parentIndex = meta.getInteger("parent_index");
                final int seenOrder = i;
                parentMap.compute(parentId, (id, existing) -> {
                    if (existing == null) {
                        return new ParentAggregateEntry(id, parentText, parentIndex, meta.copy(), 1, seenOrder);
                    } else {
                        existing.incrementChildCount();
                        return existing;
                    }
                });
            } else {
                nonParentSegments.add(child);
            }
        }

        List<ParentAggregateEntry> entries = new ArrayList<>(parentMap.values());
        // 按照父分块原始 parent_index 优先排序，无索引时按首遇次序排序
        entries.sort(Comparator.comparingInt(e -> e.parentIndex != null ? e.parentIndex : e.firstSeenOrder));

        List<TextSegment> result = new ArrayList<>(entries.size() + nonParentSegments.size());
        for (ParentAggregateEntry entry : entries) {
            Metadata parentMeta = entry.baseMetadata != null ? entry.baseMetadata.copy() : new Metadata();
            parentMeta.put("parent_id", entry.parentId);
            if (entry.parentIndex != null) {
                parentMeta.put("parent_index", entry.parentIndex);
            }
            parentMeta.put("matched_child_count", entry.matchedChildCount);
            parentMeta.remove("child_index");
            parentMeta.remove("parent_text");

            result.add(TextSegment.from(entry.parentText, parentMeta));
        }

        result.addAll(nonParentSegments);
        return Collections.unmodifiableList(result);
    }

    /**
     * 将检索命中的片段聚合为直接喂给大模型上下文的完整提示词字符串
     *
     * @param segments 检索命中的 TextSegment 集合
     * @return 以默认分隔符 {@link #DEFAULT_DELIMITER} 拼接的父级宏观上下文
     */
    public static String aggregateToPromptContext(List<TextSegment> segments) {
        return aggregateToPromptContext(segments, DEFAULT_DELIMITER);
    }

    /**
     * 将检索命中的片段聚合为直接喂给大模型上下文的完整提示词字符串
     *
     * @param segments  检索命中的 TextSegment 集合
     * @param delimiter 段落间拼接分隔符 (为 null 时自动使用 {@link #DEFAULT_DELIMITER})
     * @return 拼接完成的完整宏观上下文字符串
     */
    public static String aggregateToPromptContext(List<TextSegment> segments, String delimiter) {
        List<String> texts = reconstructParentTexts(segments);
        if (texts.isEmpty()) {
            return "";
        }
        String sep = delimiter != null ? delimiter : DEFAULT_DELIMITER;
        return String.join(sep, texts);
    }

    private static class ParentAggregateEntry {
        private final String parentId;
        private final String parentText;
        private final Integer parentIndex;
        private final Metadata baseMetadata;
        private int matchedChildCount;
        private final int firstSeenOrder;

        ParentAggregateEntry(String parentId, String parentText, Integer parentIndex,
                             Metadata baseMetadata, int matchedChildCount, int firstSeenOrder) {
            this.parentId = parentId;
            this.parentText = parentText;
            this.parentIndex = parentIndex;
            this.baseMetadata = baseMetadata;
            this.matchedChildCount = matchedChildCount;
            this.firstSeenOrder = firstSeenOrder;
        }

        void incrementChildCount() {
            this.matchedChildCount++;
        }
    }
}
