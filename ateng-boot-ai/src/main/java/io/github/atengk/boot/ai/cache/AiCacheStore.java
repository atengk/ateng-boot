package io.github.atengk.boot.ai.cache;

import java.time.Duration;
import java.util.Optional;

/**
 * AI 双级响应缓存底层存储 SPI 接口
 * <p>
 * 提供单机内存有界 LRU 与分布式 Redis TTL 双模实现契约，与主 RAG 知识库 {@code EmbeddingStore} 保持 100% 物理隔离。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-22
 */
public interface AiCacheStore {

    /**
     * 根据精确复合 Hash 键检索未过期缓存条目
     *
     * @param exactKey 精确复合 Hash 键
     * @return 命中条目，未命中或已过期返回 {@link Optional#empty()}
     */
    Optional<AiCacheEntry> getExact(String exactKey);

    /**
     * 在指定防串话作用域内执行向量余弦相似度语义检索
     *
     * @param scopeKey            隔离作用域键（已强绑定 tenantId + modelName + sampling + systemDigest）
     * @param queryVector         当前提问文本稠密向量
     * @param similarityThreshold 最低余弦相似度阈值
     * @return 满足阈值的最高分未过期命中结果，未命中返回 {@link Optional#empty()}
     */
    Optional<AiCacheLookupResult> searchSemantic(String scopeKey, float[] queryVector, double similarityThreshold);

    /**
     * 写入或覆盖缓存条目并绑定存活 TTL
     *
     * @param entry 缓存条目
     * @param ttl   存活时间
     */
    void put(AiCacheEntry entry, Duration ttl);

    /**
     * 驱逐指定租户名下的所有缓存条目
     *
     * @param tenantId 租户标识
     */
    void evictByTenant(String tenantId);

    /**
     * 清空当前存储介质中的所有缓存条目与索引
     */
    void clearAll();

    /**
     * 获取当前存储中活跃条目数量（近似或精确值）
     *
     * @return 条目数
     */
    long size();

    /**
     * 标识当前存储是否为分布式存储实现
     *
     * @return 分布式实现返回 true，单机内存实现返回 false
     */
    boolean isDistributed();

    /**
     * 计算两个稠密特征向量的余弦相似度 [-1.0, 1.0]
     *
     * @param a 向量 A
     * @param b 向量 B
     * @return 余弦相似度分值；若向量为空、维度不一致或模长为 0 则返回 0.0
     */
    static double cosineSimilarity(float[] a, float[] b) {
        if (a == null || b == null || a.length == 0 || a.length != b.length) {
            return 0.0;
        }
        double dot = 0.0;
        double normA = 0.0;
        double normB = 0.0;
        for (int i = 0; i < a.length; i++) {
            double va = a[i];
            double vb = b[i];
            dot += va * vb;
            normA += va * va;
            normB += vb * vb;
        }
        if (normA <= 0.0 || normB <= 0.0) {
            return 0.0;
        }
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }
}
