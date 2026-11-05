package io.github.atengk.boot.ai.rag.ingest;

/**
 * RAG 知识库文档 Ingestion 幂等去重生命周期治理策略
 *
 * @author Ateng
 * @since 2026-11-05
 */
public enum DeduplicationPolicy {

    /**
     * 覆盖策略 (默认)：入库前先依据 doc_id 物理级联清理已存在的旧版本所有向量切片，随后写入全新切片
     */
    OVERWRITE,

    /**
     * 跳过策略：若当前文档与已有记录的 doc_id 且内容 SHA-256 哈希完全一致，则判定内容未变，跳过重复解析与切片入库
     */
    SKIP,

    /**
     * 追加策略：不执行任何历史数据校验或清理，直接增量追加新切片
     */
    APPEND
}
