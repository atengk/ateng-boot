package io.github.atengk.boot.ai.rag.splitter;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import org.springframework.util.Assert;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 原生父子分级智能切片器 (ParentChildSplitter)
 * <p>
 * 彻底解决 RAG 知识检索领域的“精度与上下文不可兼得”困境：
 * 1. 宏观父分块 (Parent Chunk)：保留长篇幅（如 1000~2000 字符）宏观上下文语义；
 * 2. 微观子分块 (Child Chunk)：细粒度（如 200~400 字符）切分用于向量化索引，极大提升向量相似度匹准率；
 * 3. 自包含父上下文 (Self-Contained Parent Context)：子块元数据嵌入 {@code parent_id} 与 {@code parent_text}，
 *    无需额外架设外部 Redis/KV 存储，即可单机自洽还原完整宏观上下文。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-05
 */
public class ParentChildSplitter implements DocumentSplitter {

    public static final int DEFAULT_PARENT_CHUNK_SIZE = 1200;
    public static final int DEFAULT_PARENT_CHUNK_OVERLAP = 100;
    public static final int DEFAULT_CHILD_CHUNK_SIZE = 300;
    public static final int DEFAULT_CHILD_CHUNK_OVERLAP = 30;

    private final DocumentSplitter parentSplitter;
    private final DocumentSplitter childSplitter;
    private final boolean selfContainedParent;

    /**
     * 使用生产推荐默认配置创建父子切片器
     * (父块 1200/100，子块 300/30，开启自包含父文本)
     */
    public ParentChildSplitter() {
        this(DEFAULT_PARENT_CHUNK_SIZE, DEFAULT_PARENT_CHUNK_OVERLAP,
                DEFAULT_CHILD_CHUNK_SIZE, DEFAULT_CHILD_CHUNK_OVERLAP, true);
    }

    /**
     * 使用指定父子字符窗口创建父子切片器
     *
     * @param parentChunkSize    父切片最大字符数 (必须 > 0)
     * @param parentChunkOverlap 父切片重叠字符数 (必须 >= 0 且 < parentChunkSize)
     * @param childChunkSize     子切片最大字符数 (必须 > 0 且 < parentChunkSize)
     * @param childChunkOverlap  子切片重叠字符数 (必须 >= 0 且 < childChunkSize)
     */
    public ParentChildSplitter(int parentChunkSize, int parentChunkOverlap,
                               int childChunkSize, int childChunkOverlap) {
        this(parentChunkSize, parentChunkOverlap, childChunkSize, childChunkOverlap, true);
    }

    /**
     * 使用指定父子字符窗口与自包含开关创建父子切片器
     *
     * @param parentChunkSize     父切片最大字符数 (必须 > 0)
     * @param parentChunkOverlap  父切片重叠字符数 (必须 >= 0 且 < parentChunkSize)
     * @param childChunkSize      子切片最大字符数 (必须 > 0 且 < parentChunkSize)
     * @param childChunkOverlap   子切片重叠字符数 (必须 >= 0 且 < childChunkSize)
     * @param selfContainedParent 是否在子分块元数据中直接嵌入父分块完整文本 (默认 true)
     */
    public ParentChildSplitter(int parentChunkSize, int parentChunkOverlap,
                               int childChunkSize, int childChunkOverlap, boolean selfContainedParent) {
        Assert.isTrue(parentChunkSize > 0, "parentChunkSize 必须大于 0");
        Assert.isTrue(parentChunkOverlap >= 0, "parentChunkOverlap 不能为负数");
        Assert.isTrue(parentChunkOverlap < parentChunkSize, "parentChunkOverlap 必须小于 parentChunkSize");
        Assert.isTrue(childChunkSize > 0, "childChunkSize 必须大于 0");
        Assert.isTrue(childChunkOverlap >= 0, "childChunkOverlap 不能为负数");
        Assert.isTrue(childChunkOverlap < childChunkSize, "childChunkOverlap 必须小于 childChunkSize");
        Assert.isTrue(childChunkSize < parentChunkSize, "childChunkSize 必须小于 parentChunkSize");

        this.parentSplitter = new RecursiveCharacterSplitter(parentChunkSize, parentChunkOverlap);
        this.childSplitter = new RecursiveCharacterSplitter(childChunkSize, childChunkOverlap);
        this.selfContainedParent = selfContainedParent;
    }

    /**
     * 使用自定义父子委派切片器创建父子分级切片器
     *
     * @param parentSplitter 宏观父分块切片器 (不能为空)
     * @param childSplitter  微观子分块切片器 (不能为空)
     */
    public ParentChildSplitter(DocumentSplitter parentSplitter, DocumentSplitter childSplitter) {
        this(parentSplitter, childSplitter, true);
    }

    /**
     * 全参数构造函数
     *
     * @param parentSplitter      宏观父分块切片器 (不能为空)
     * @param childSplitter       微观子分块切片器 (不能为空)
     * @param selfContainedParent 是否在子分块元数据中直接嵌入父分块完整文本
     */
    public ParentChildSplitter(DocumentSplitter parentSplitter, DocumentSplitter childSplitter, boolean selfContainedParent) {
        Assert.notNull(parentSplitter, "parentSplitter 不能为空");
        Assert.notNull(childSplitter, "childSplitter 不能为空");
        this.parentSplitter = parentSplitter;
        this.childSplitter = childSplitter;
        this.selfContainedParent = selfContainedParent;
    }

    @Override
    public List<TextSegment> split(String text, Metadata metadata) {
        return splitHierarchy(text, metadata).children();
    }

    /**
     * 针对 LangChain4j Document 执行两级切片并返回完整的父子关联聚合体
     *
     * @param document 待切片文档
     * @return 包含父子两级片段及映射关系的 ParentChildResult
     */
    public ParentChildResult splitHierarchy(Document document) {
        if (document == null) {
            return new ParentChildResult(Collections.emptyList(), Collections.emptyList(), Collections.emptyMap(), Collections.emptyMap());
        }
        return splitHierarchy(document.text(), document.metadata());
    }

    /**
     * 执行两级层级切分，返回完整的父子关联聚合体
     *
     * @param text     原始长文本
     * @param metadata 初始元数据
     * @return 包含父子两级片段及映射关系的 ParentChildResult
     */
    public ParentChildResult splitHierarchy(String text, Metadata metadata) {
        if (text == null || text.isBlank()) {
            return new ParentChildResult(Collections.emptyList(), Collections.emptyList(), Collections.emptyMap(), Collections.emptyMap());
        }

        // 1. 生成宏观父分块集合
        List<TextSegment> rawParents = this.parentSplitter.split(text, metadata != null ? metadata.copy() : new Metadata());
        if (rawParents.isEmpty()) {
            return new ParentChildResult(Collections.emptyList(), Collections.emptyList(), Collections.emptyMap(), Collections.emptyMap());
        }

        int totalParents = rawParents.size();
        List<TextSegment> enrichedParents = new ArrayList<>(totalParents);
        List<TextSegment> allChildren = new ArrayList<>();
        Map<String, List<TextSegment>> parentIdToChildren = new LinkedHashMap<>();
        Map<String, TextSegment> parentIdToParent = new LinkedHashMap<>();

        // 2. 为每个父分块分配全局唯一 parent_id 并细切微观子分块
        for (int pIdx = 0; pIdx < totalParents; pIdx++) {
            TextSegment rawParent = rawParents.get(pIdx);
            String parentId = generateParentId(rawParent.metadata(), pIdx);

            Metadata parentMeta = rawParent.metadata() != null ? rawParent.metadata().copy() : new Metadata();
            parentMeta.put("parent_id", parentId);
            parentMeta.put("parent_index", pIdx);
            parentMeta.put("total_parents", totalParents);
            parentMeta.put("is_parent", "true");

            TextSegment parentSeg = TextSegment.from(rawParent.text(), parentMeta);
            enrichedParents.add(parentSeg);
            parentIdToParent.put(parentId, parentSeg);

            // 3. 对当前父分块进行微观子分块细切
            Metadata childBaseMeta = parentMeta.copy();
            childBaseMeta.remove("is_parent");
            if (this.selfContainedParent) {
                childBaseMeta.put("parent_text", parentSeg.text());
                childBaseMeta.put("parent_char_count", parentSeg.text().length());
            }

            List<TextSegment> rawChildren = this.childSplitter.split(parentSeg.text(), childBaseMeta);
            int totalChildrenInParent = rawChildren.size();
            List<TextSegment> childrenForParent = new ArrayList<>(totalChildrenInParent);

            for (int cIdx = 0; cIdx < totalChildrenInParent; cIdx++) {
                TextSegment rawChild = rawChildren.get(cIdx);
                Metadata childMeta = rawChild.metadata() != null ? rawChild.metadata().copy() : new Metadata();
                childMeta.put("parent_id", parentId);
                childMeta.put("parent_index", pIdx);
                childMeta.put("total_parents", totalParents);
                childMeta.put("child_index", cIdx);
                childMeta.put("total_children_in_parent", totalChildrenInParent);

                TextSegment childSeg = TextSegment.from(rawChild.text(), childMeta);
                childrenForParent.add(childSeg);
                allChildren.add(childSeg);
            }

            parentIdToChildren.put(parentId, Collections.unmodifiableList(childrenForParent));
        }

        // 4. 注入全局子分块序号 (global_child_index, total_global_children) 及规范 chunk_index
        int totalGlobalChildren = allChildren.size();
        List<TextSegment> finalChildren = new ArrayList<>(totalGlobalChildren);
        for (int gIdx = 0; gIdx < totalGlobalChildren; gIdx++) {
            TextSegment child = allChildren.get(gIdx);
            Metadata finalChildMeta = child.metadata().copy();
            finalChildMeta.put("chunk_index", gIdx);
            finalChildMeta.put("global_child_index", gIdx);
            finalChildMeta.put("total_global_children", totalGlobalChildren);
            finalChildren.add(TextSegment.from(child.text(), finalChildMeta));
        }

        return new ParentChildResult(
                enrichedParents,
                finalChildren,
                parentIdToChildren,
                parentIdToParent
        );
    }

    private static String generateParentId(Metadata metadata, int index) {
        String docId = metadata != null ? metadata.getString("doc_id") : null;
        String uuidSuffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        if (docId != null && !docId.isBlank()) {
            return docId + ":p" + index + ":" + uuidSuffix;
        }
        return "parent:p" + index + ":" + uuidSuffix;
    }

    public DocumentSplitter getParentSplitter() {
        return parentSplitter;
    }

    public DocumentSplitter getChildSplitter() {
        return childSplitter;
    }

    public boolean isSelfContainedParent() {
        return selfContainedParent;
    }
}
