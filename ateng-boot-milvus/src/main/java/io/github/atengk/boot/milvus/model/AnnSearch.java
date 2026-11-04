package io.github.atengk.boot.milvus.model;

import io.milvus.v2.service.vector.request.AnnSearchReq;
import io.milvus.v2.service.vector.request.data.BaseVector;
import io.milvus.v2.service.vector.request.data.FloatVec;
import io.milvus.v2.service.vector.request.data.SparseFloatVec;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

import java.io.Serializable;
import java.util.Collections;
import java.util.List;
import java.util.SortedMap;

/**
 * 多向量混合检索子检索模型 (支持稠密向量与稀疏向量通道)
 *
 * @author Ateng
 * @since 2026-11-04
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AnnSearch implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 目标向量列字段名称
     */
    private String vectorFieldName;

    /**
     * 当前子检索路数的候选召回上限 TopK
     */
    @Builder.Default
    private int topK = 10;

    /**
     * 当前子检索专属标量过滤表达式 (可选)
     */
    private String expr;

    /**
     * 查询向量 (稠密 FloatVec 或稀疏 SparseFloatVec)
     */
    private BaseVector vector;

    /**
     * 专属检索超参数 JSON 字符串 (如 "{\"nprobe\": 10}")
     */
    private String params;

    /**
     * 构建稠密向量子检索
     *
     * @param vectorFieldName 向量列名
     * @param vector          浮点向量列表
     * @param topK            召回数量
     * @return AnnSearch 实例
     */
    public static AnnSearch of(String vectorFieldName, List<Float> vector, int topK) {
        return of(vectorFieldName, vector, topK, null);
    }

    /**
     * 构建稠密向量子检索 (含标量过滤表达式)
     *
     * @param vectorFieldName 向量列名
     * @param vector          浮点向量列表
     * @param topK            召回数量
     * @param expr            过滤表达式
     * @return AnnSearch 实例
     */
    public static AnnSearch of(String vectorFieldName, List<Float> vector, int topK, String expr) {
        Assert.hasText(vectorFieldName, "vectorFieldName must not be empty");
        Assert.notNull(vector, "vector must not be null");
        return AnnSearch.builder()
                .vectorFieldName(vectorFieldName.trim())
                .vector(new FloatVec(vector))
                .topK(topK)
                .expr(expr)
                .build();
    }

    /**
     * 构建稠密向量子检索 (原生 float 数组)
     *
     * @param vectorFieldName 向量列名
     * @param vector          原生 float 数组
     * @param topK            召回数量
     * @return AnnSearch 实例
     */
    public static AnnSearch of(String vectorFieldName, float[] vector, int topK) {
        return of(vectorFieldName, vector, topK, null);
    }

    /**
     * 构建稠密向量子检索 (原生 float 数组及标量过滤表达式)
     *
     * @param vectorFieldName 向量列名
     * @param vector          原生 float 数组
     * @param topK            召回数量
     * @param expr            过滤表达式
     * @return AnnSearch 实例
     */
    public static AnnSearch of(String vectorFieldName, float[] vector, int topK, String expr) {
        Assert.hasText(vectorFieldName, "vectorFieldName must not be empty");
        Assert.notNull(vector, "vector must not be null");
        return AnnSearch.builder()
                .vectorFieldName(vectorFieldName.trim())
                .vector(new FloatVec(vector))
                .topK(topK)
                .expr(expr)
                .build();
    }

    /**
     * 构建稀疏向量子检索 (BM25 或 SPLADE 稀疏字典)
     *
     * @param vectorFieldName 稀疏向量列名
     * @param sparseVector    稀疏向量有序字典 (termId -> weight)
     * @param topK            召回数量
     * @return AnnSearch 实例
     */
    public static AnnSearch ofSparse(String vectorFieldName, SortedMap<Long, Float> sparseVector, int topK) {
        return ofSparse(vectorFieldName, sparseVector, topK, null);
    }

    /**
     * 构建稀疏向量子检索 (含标量过滤表达式)
     *
     * @param vectorFieldName 稀疏向量列名
     * @param sparseVector    稀疏向量有序字典
     * @param topK            召回数量
     * @param expr            过滤表达式
     * @return AnnSearch 实例
     */
    public static AnnSearch ofSparse(String vectorFieldName, SortedMap<Long, Float> sparseVector, int topK, String expr) {
        Assert.hasText(vectorFieldName, "vectorFieldName must not be empty");
        Assert.notNull(sparseVector, "sparseVector must not be null");
        return AnnSearch.builder()
                .vectorFieldName(vectorFieldName.trim())
                .vector(new SparseFloatVec(sparseVector))
                .topK(topK)
                .expr(expr)
                .build();
    }

    /**
     * 转换为 Milvus 原生 AnnSearchReq
     *
     * @param effectiveExpr 最终生效的标量过滤表达式 (若为空则取默认 expr)
     * @return AnnSearchReq 实例
     */
    public AnnSearchReq toAnnSearchReq(String effectiveExpr) {
        AnnSearchReq.AnnSearchReqBuilder<?, ?> builder = AnnSearchReq.builder()
                .vectorFieldName(vectorFieldName)
                .topK(topK)
                .vectors(Collections.singletonList(vector));
        String targetExpr = StringUtils.hasText(effectiveExpr) ? effectiveExpr : this.expr;
        if (StringUtils.hasText(targetExpr)) {
            builder.expr(targetExpr.trim());
        }
        if (StringUtils.hasText(params)) {
            builder.params(params.trim());
        }
        return builder.build();
    }
}
