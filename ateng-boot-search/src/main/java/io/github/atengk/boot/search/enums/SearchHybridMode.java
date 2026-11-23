package io.github.atengk.boot.search.enums;

import io.github.atengk.boot.core.enums.IBaseEnum;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * AI 混合检索多路召回融合模式枚举
 *
 * @author Ateng
 * @since 2026-11-20
 */
@Getter
@AllArgsConstructor
public enum SearchHybridMode implements IBaseEnum<String> {

    /**
     * 客户端倒数排名融合（默认）：单次 {@code _msearch} 并发召回 BM25 与 kNN 结果并在内存端执行 RRF 重排，零付费许可证与 Pipeline 依赖
     */
    CLIENT_RRF("CLIENT_RRF", "客户端 RRF 融合"),

    /**
     * 客户端加权归一化融合：单次 {@code _msearch} 并发召回后各自最大值归一化并按权重求和重排
     */
    CLIENT_WEIGHTED("CLIENT_WEIGHTED", "客户端加权融合"),

    /**
     * 搜索引擎服务端原生 RRF 融合（需 Elasticsearch 8.8+ 对应许可证支持）
     */
    NATIVE_RRF("NATIVE_RRF", "服务端原生 RRF 融合");

    private final String code;
    private final String label;
}
