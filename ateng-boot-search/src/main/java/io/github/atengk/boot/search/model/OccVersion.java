package io.github.atengk.boot.search.model;

import java.io.Serializable;

/**
 * 乐观并发控制 (OCC) 版本元数据载荷
 * <p>
 * 封装 Elasticsearch 内部由序列号 (Sequence Number) 与分片世代 (Primary Term) 组成的强一致 OCC 校验元数据。
 *
 * @param seqNo       文档序列号
 * @param primaryTerm 分片主分片世代
 * @author Ateng
 * @since 2026-10-25
 */
public record OccVersion(Long seqNo, Long primaryTerm) implements Serializable {

    /**
     * 空版本常量（表示不启用 OCC 校验）
     */
    public static final OccVersion EMPTY = new OccVersion(null, null);

    /**
     * 构建 OCC 版本对象
     *
     * @param seqNo       序列号
     * @param primaryTerm 主分片世代
     * @return OccVersion 实例
     */
    public static OccVersion of(Long seqNo, Long primaryTerm) {
        if (seqNo == null && primaryTerm == null) {
            return EMPTY;
        }
        return new OccVersion(seqNo, primaryTerm);
    }

    /**
     * 从 SearchHit 提取 OCC 版本对象
     *
     * @param hit 命中文档
     * @return OccVersion 实例
     */
    public static OccVersion fromHit(SearchHit<?> hit) {
        if (hit == null) {
            return EMPTY;
        }
        return of(hit.getSeqNo(), hit.getPrimaryTerm());
    }

    /**
     * 是否指定了完整的 OCC 校验参数
     *
     * @return true 表示 seqNo 与 primaryTerm 均不为 null
     */
    public boolean isSpecified() {
        return seqNo != null && primaryTerm != null;
    }
}
