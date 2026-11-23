package io.github.atengk.boot.milvus.model;

import io.milvus.v2.service.vector.request.ranker.BaseRanker;
import io.milvus.v2.service.vector.request.ranker.RRFRanker;
import io.milvus.v2.service.vector.request.ranker.WeightedRanker;
import org.springframework.util.Assert;

import java.util.Arrays;
import java.util.List;

/**
 * 向量多路召回重排策略工厂 (RRF / Weighted)
 *
 * @author Ateng
 * @since 2026-11-04
 */
public final class ReRanker {

    private ReRanker() {
    }

    /**
     * RRF (Reciprocal Rank Fusion) 倒数排名融合重排算法
     * 公式: score = sum(1.0 / (k + rank_i))
     *
     * @param k 平滑常数因子 (通常推荐取 60)
     * @return 官方 RRFRanker
     */
    public static BaseRanker rrf(int k) {
        Assert.isTrue(k > 0, "k must be positive");
        return new RRFRanker(k);
    }

    /**
     * RRF 默认平滑因子 (k = 60)
     *
     * @return 官方 RRFRanker
     */
    public static BaseRanker rrf() {
        return new RRFRanker(60);
    }

    /**
     * 加权评分融合重排算法 (Weighted Ranker)
     *
     * @param weights 权重列表 (长度必须与子检索路数一致)
     * @return 官方 WeightedRanker
     */
    public static BaseRanker weighted(List<Float> weights) {
        Assert.notEmpty(weights, "weights must not be empty");
        return new WeightedRanker(weights);
    }

    /**
     * 加权评分融合重排算法 (可变参数重载)
     *
     * @param weights 权重数组
     * @return 官方 WeightedRanker
     */
    public static BaseRanker weighted(Float... weights) {
        Assert.notNull(weights, "weights must not be null");
        Assert.isTrue(weights.length > 0, "weights must not be empty");
        return new WeightedRanker(Arrays.asList(weights));
    }
}
